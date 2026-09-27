package com.devbangs.onedevs.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.data.listings.ListingRepository
import com.devbangs.onedevs.data.play.packageOrNull
import com.devbangs.onedevs.data.play.parseOptInLink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The address developers put into their own Play Console.
 *
 * This is the inversion that matters: OneDevs owns one Google Group, and a
 * developer adds it to their closed test's tester list. Every OneDevs tester is
 * already in the group, so one paste in Play Console reaches all of them -- and
 * there is no per-app group for anyone to supply, mistype, or forget.
 */
const val OneDevsTesterGroup = "onedevs-testers@googlegroups.com"

class AddListingViewModel(private val repository: ListingRepository) : ViewModel() {

    /**
     * Fixed when the form opens so a chosen icon has somewhere to live before
     * anything is saved. A listing abandoned halfway leaves one orphaned file,
     * which is cheaper than writing the record early to hold a filename.
     */
    private var draftId: String = UUID.randomUUID().toString()
    private var loaded = false

    var title by mutableStateOf("")
    var category by mutableStateOf("")
    var testNote by mutableStateOf("")
    var optInLink by mutableStateOf("")

    var iconPath by mutableStateOf<String?>(null)
        private set
    var iconImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /** The package, read out of the opt-in link rather than asked for twice. */
    val packageName: String?
        get() = parseOptInLink(optInLink)?.packageOrNull()

    val linkLooksWrong: Boolean
        get() = optInLink.isNotBlank() && packageName == null

    /**
     * Fills the form from a listing already on file. Keeps its id, so saving
     * replaces that record rather than filing a second copy of the same app.
     */
    fun load(id: String) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val existing = repository.find(id) ?: return@launch
            draftId = existing.id
            title = existing.title
            category = existing.category
            testNote = existing.testNote.orEmpty()
            optInLink = existing.optInLink.orEmpty()
            iconPath = existing.iconPath
            iconImage = existing.iconPath?.let { path ->
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }
            }
        }
    }

    fun setIcon(context: Context, uri: Uri) {
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: return@runCatching null
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching null
                    // 512 because that is what Play asks for, and because an
                    // untouched camera-sized PNG in app storage per listing adds
                    // up faster than anyone expects.
                    val square = decoded.scale(512, 512)
                    val dir = File(context.filesDir, "icons").apply { mkdirs() }
                    val file = File(dir, "$draftId.png")
                    file.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    file.absolutePath to square.asImageBitmap()
                }.getOrNull()
            }
            if (prepared != null) {
                iconPath = prepared.first
                iconImage = prepared.second
            }
        }
    }

    fun canSave(channel: Channel): Boolean =
        !saving &&
            title.isNotBlank() &&
            category.isNotBlank() &&
            (channel == Channel.Live || packageName != null)

    fun save(channel: Channel, onSaved: () -> Unit) {
        if (!canSave(channel)) return
        saving = true
        viewModelScope.launch {
            repository.add(
                Listing(
                    id = draftId,
                    packageName = packageName.orEmpty(),
                    title = title.trim(),
                    category = category.trim(),
                    channel = channel,
                    optInLink = optInLink.trim().ifBlank { null },
                    testNote = testNote.trim().ifBlank { null },
                    createdAt = System.currentTimeMillis(),
                    iconPath = iconPath,
                ),
            )
            saving = false
            onSaved()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as OneDevsApplication
                AddListingViewModel(app.listings)
            }
        }
    }
}

@Composable
fun AddListingScreen(
    channel: Channel,
    listingId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddListingViewModel = viewModel(factory = AddListingViewModel.Factory),
) {
    val testing = channel == Channel.Testing
    val context = LocalContext.current
    LaunchedEffect(listingId) { listingId?.let(viewModel::load) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.setIcon(context, it) } }

    // No page title. The button at the foot says what this does, the tab says
    // where you are, and a heading repeating it costs 64dp -- which is the
    // difference between this form fitting a phone and not.
    //
    // The scroll is a safety net rather than the layout. At default text size
    // everything here is visible at once on a 360dp phone; at a large font
    // scale it has somewhere to go instead of being clipped.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // Without this the form keeps its full height behind the keyboard,
            // so the fields below it have nowhere to scroll to and simply stay
            // covered. imePadding shrinks the scrollable area to what is
            // actually visible, and Compose then brings the focused field up.
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
    ) {
        IconDropzone(
            image = viewModel.iconImage,
            onPick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(12.dp))
        FormField(
            value = viewModel.title,
            onValueChange = { viewModel.title = it },
            label = stringResource(R.string.add_app_name),
            leading = R.drawable.ic_squares_four,
        )
        Spacer(Modifier.height(10.dp))
        FormField(
            value = viewModel.category,
            onValueChange = { viewModel.category = it },
            label = stringResource(R.string.add_category),
            leading = R.drawable.ic_tag,
        )
        Spacer(Modifier.height(10.dp))
        FormField(
            value = viewModel.testNote,
            onValueChange = { viewModel.testNote = it },
            label = stringResource(R.string.add_note),
            leading = R.drawable.ic_clipboard_text,
            singleLine = false,
            minLines = 2,
        )

        if (testing) {
            Spacer(Modifier.height(10.dp))
            FormField(
                value = viewModel.optInLink,
                onValueChange = { viewModel.optInLink = it },
                label = stringResource(R.string.add_optin_link),
            leading = R.drawable.ic_globe,
                isError = viewModel.linkLooksWrong,
                supporting = if (viewModel.linkLooksWrong) {
                    stringResource(R.string.add_optin_invalid)
                } else {
                    viewModel.packageName
                },
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(12.dp))
            GroupAddressCard()
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.save(channel, onDone) },
            enabled = viewModel.canSave(channel),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        listingId != null -> R.string.add_save_changes
                        testing -> R.string.add_testing_title
                        else -> R.string.add_live_title
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * One field, one shape, everywhere on this form.
 *
 * Colour is spent only where something is interactive: a hairline border in
 * outlineVariant at rest, the brand blue while focused, and no tint anywhere
 * else. A filled blue container on every input puts the loudest colour on the
 * parts that are doing nothing, which is what makes a form read as a mockup.
 */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leading: Int,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = {
            // Boxed, but in neutral rather than blue. The glyph is there to
            // name the field at a glance; a tinted container per row would put
            // the accent colour on five things that cannot be interacted with.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(leading),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The icon, centred at 72dp.
 *
 * Smaller than it was. It is here to say which app this is, and every point it
 * takes is a point the fields underneath do not have -- the whole form has to
 * land above the fold without anyone scrolling to find the button.
 */
@Composable
private fun IconDropzone(
    image: ImageBitmap?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(18.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onPick)
                // Drawn after the fill, not before it. background() paints in
                // modifier order, so an outline declared earlier is painted
                // over by the surface and the box simply disappears -- which
                // reads as a deliberately borderless design rather than a bug.
                .drawWithContent {
                    drawContent()
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(18.dp.toPx()),
                        style = Stroke(
                            width = stroke,
                            pathEffect = if (image == null) {
                                PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                            } else {
                                null
                            },
                        ),
                    )
                },
        ) {
            if (image == null) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(shape),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (image == null) R.string.add_icon else R.string.add_icon_change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the developer takes to Play Console.
 *
 * One row rather than a panel. Google's own guidance is that a tester has to
 * join the group before they can opt in, so this address is the step that
 * makes the rest work -- but it is a thing to copy, not a thing to read twice.
 */
@Composable
private fun GroupAddressCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.add_group_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = OneDevsTesterGroup,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }
        TextButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(OneDevsTesterGroup, OneDevsTesterGroup),
                )
                copied = true
            },
        ) {
            Text(
                text = stringResource(
                    if (copied) R.string.add_group_copied else R.string.add_group_copy,
                ),
            )
        }
    }
}
