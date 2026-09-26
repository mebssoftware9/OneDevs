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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import com.devbangs.onedevs.data.play.OptInLink
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
    private val draftId: String = UUID.randomUUID().toString()

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
        get() = (parseOptInLink(optInLink) as? OptInLink.PlayOptIn)?.packageName

    val linkLooksWrong: Boolean
        get() = optInLink.isNotBlank() && packageName == null

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
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddListingViewModel = viewModel(factory = AddListingViewModel.Factory),
) {
    val testing = channel == Channel.Testing
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.setIcon(context, it) } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 28.dp),
    ) {
        Text(
            text = stringResource(if (testing) R.string.add_testing_title else R.string.add_live_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                if (testing) R.string.add_testing_subtitle else R.string.add_live_subtitle,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        IconDropzone(
            image = viewModel.iconImage,
            onPick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(20.dp))
        FormField(
            value = viewModel.title,
            onValueChange = { viewModel.title = it },
            label = stringResource(R.string.add_app_name),
        )
        Spacer(Modifier.height(12.dp))
        FormField(
            value = viewModel.category,
            onValueChange = { viewModel.category = it },
            label = stringResource(R.string.add_category),
        )
        Spacer(Modifier.height(12.dp))
        FormField(
            value = viewModel.testNote,
            onValueChange = { viewModel.testNote = it },
            label = stringResource(R.string.add_note),
            singleLine = false,
            minLines = 3,
        )

        if (testing) {
            Spacer(Modifier.height(12.dp))
            FormField(
                value = viewModel.optInLink,
                onValueChange = { viewModel.optInLink = it },
                label = stringResource(R.string.add_optin_link),
                placeholder = stringResource(R.string.add_optin_hint),
                isError = viewModel.linkLooksWrong,
                supporting = if (viewModel.linkLooksWrong) {
                    stringResource(R.string.add_optin_invalid)
                } else {
                    viewModel.packageName
                },
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(20.dp))
            GroupAddressCard()
        }

        Spacer(Modifier.height(24.dp))
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
                    if (testing) R.string.add_testing_title else R.string.add_live_title,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

/**
 * One field, one shape, everywhere on this form.
 *
 * Colour is spent only where it means something: the border is a hairline in
 * outlineVariant at rest and the brand blue while focused, and nothing else on
 * the field carries a tint. A filled blue container on every input is the thing
 * that makes a form read as a mockup -- it puts the loudest colour on the parts
 * that are doing nothing.
 */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
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
        placeholder = placeholder?.let { { Text(it) } },
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
 * The icon, centred and 96dp.
 *
 * Deliberately not bigger. It is here to say which app this is, and a dropzone
 * that dominates the form implies the icon is the work -- it is the smallest
 * part of it. Dashed hairline rather than a filled blue panel: the dashes carry
 * "put something here" on their own, without spending the accent colour on a
 * control that is optional.
 */
@Composable
private fun IconDropzone(
    image: ImageBitmap?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(22.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(shape)
                .then(
                    if (image == null) {
                        Modifier.drawBehind {
                            drawRoundRect(
                                color = outline,
                                cornerRadius = CornerRadius(22.dp.toPx()),
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                                    ),
                                ),
                            )
                        }
                    } else {
                        Modifier.border(1.dp, outline, shape)
                    },
                )
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onPick),
        ) {
            if (image == null) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(shape),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (image == null) R.string.add_icon else R.string.add_icon_change),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.add_icon_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * What the developer takes to Play Console.
 *
 * The one thing on this form that is not a question. It is the group address
 * they paste into their closed test's tester list, and it is here rather than
 * in help text because it is the step that actually connects the two products.
 */
@Composable
private fun GroupAddressCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.add_group_heading),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.add_group_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = OneDevsTesterGroup,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
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
}
