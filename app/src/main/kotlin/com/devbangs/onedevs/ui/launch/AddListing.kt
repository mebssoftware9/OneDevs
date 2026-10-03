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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.devbangs.onedevs.ui.theme.oneDevsColors
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.listings.CheckRecord
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.data.listings.ListingRepository
import com.devbangs.onedevs.data.listings.megabytesOf
import com.devbangs.onedevs.data.listings.parseMegabytes
import com.devbangs.onedevs.data.play.PlayListing
import com.devbangs.onedevs.data.play.PlayListings
import com.devbangs.onedevs.data.play.packageOrNull
import com.devbangs.onedevs.data.play.href
import com.devbangs.onedevs.data.play.parseOptInLink
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.Waiting
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

/**
 * The whole rule, in one place and covered by tests, because it is the only
 * part of this screen that can refuse a person's work.
 *
 * A 200 is the single unambiguous answer Play gives anonymously: something
 * public is there, so the app is live, so it is not a closed test. A 404 is
 * returned identically for a real closed test and for a package that never
 * existed, so it can establish nothing. A failed request establishes less.
 */
internal fun blocksListing(channel: Channel, result: PlayListing?): Boolean =
    channel == Channel.Testing && result == PlayListing.Live

/** Where the Play check has got to for the link currently in the field. */
sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class Done(val result: PlayListing, val checkedAt: Long) : CheckState
}

class AddListingViewModel(
    private val repository: ListingRepository,
    private val backend: Backend,
    private val account: AccountState,
) : ViewModel() {

    /**
     * Fixed when the form opens so a chosen icon has somewhere to live before
     * anything is saved. A listing abandoned halfway leaves one orphaned file,
     * which is cheaper than writing the record early to hold a filename.
     */
    private var draftId: String = UUID.randomUUID().toString()
    private var loaded = false

    var title by mutableStateOf("")
    var category by mutableStateOf("")
    var sizeMb by mutableStateOf("")
    var testNote by mutableStateOf("")
    var optInLink by mutableStateOf("")

    var iconPath by mutableStateOf<String?>(null)
        private set

    /**
     * Where the icon already lives, for a listing being edited. The local path
     * above is a freshly chosen file waiting to be uploaded; this is one that
     * already went. Either satisfies the requirement to have an icon.
     */
    var iconUrl by mutableStateOf<String?>(null)
        private set
    var iconImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /** What asking Play about this link established, if it has been asked. */
    var check by mutableStateOf<CheckState>(CheckState.Idle)
        private set

    /** The package the current answer belongs to, so one link is asked about once. */
    private var checkedPackage: String? = null

    /** The package, read out of the opt-in link rather than asked for twice. */
    val packageName: String?
        get() = parseOptInLink(optInLink)?.packageOrNull()

    val linkLooksWrong: Boolean
        get() = optInLink.isNotBlank() && packageName == null

    /**
     * Fills the form from a listing already on file. Keeps its id, so saving
     * replaces that record rather than filing a second copy of the same app --
     * unless [asCopy], which is the same app going on the other board: same
     * details, a record of its own.
     */
    fun load(context: Context, id: String, asCopy: Boolean = false) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val existing = repository.find(id) ?: return@launch
            if (!asCopy) draftId = existing.id
            title = existing.title
            category = existing.category
            sizeMb = existing.sizeBytes?.let(::megabytesOf).orEmpty()
            testNote = existing.testNote.orEmpty()
            optInLink = existing.optInLink.orEmpty()
            iconPath = existing.iconPath
            iconUrl = existing.iconUrl
            iconImage = existing.iconPath?.let { path ->
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }
            } ?: cachedImage(context, "icon-${existing.id}", existing.iconUrl)
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

    /**
     * Set when a save could not finish. A string id rather than a sentence:
     * a view model that hardcodes English is a view model that ships one
     * language whatever the four locale files say.
     */
    var problem by mutableStateOf<Int?>(null)
        private set

    val canSave: Boolean
        get() = !saving && title.isNotBlank() && category.isNotBlank() &&
            packageName != null &&
            // An icon is required. On a board of apps competing for a tester's
            // attention, one with no mark is one nobody looks at, and letting
            // it through helps nobody -- least of all the developer who listed it.
            (iconPath != null || iconUrl != null)

    /**
     * The only thing that stops a save.
     *
     * A confident 200 means a public listing answered, so the app is live, so
     * it is not the closed test the Testing Board is for. Nothing else blocks:
     * a 404 cannot tell a real closed test from a typo, and an unreachable Play
     * would otherwise lock out whoever has the worst connection.
     */
    fun blockedFrom(channel: Channel): Boolean =
        blocksListing(channel, (check as? CheckState.Done)?.result)

    /**
     * Asks Play once per link. Called when the field loses focus rather than on
     * every keystroke: a request per character would be both useless and rude.
     */
    /**
     * Makes sure an answer exists for the link as it stands, asking Play only
     * if there isn't one yet. Suspends, so a save can wait on it.
     */
    private suspend fun ensureChecked() {
        val pkg = packageName ?: return
        if (pkg == checkedPackage && check is CheckState.Done) return
        checkedPackage = pkg
        check = CheckState.Checking
        check = CheckState.Done(PlayListings.check(pkg), System.currentTimeMillis())
    }

    /** Asks early, so the verdict is usually on screen before the button is. */
    fun checkLink() {
        if (packageName == null) return
        viewModelScope.launch { ensureChecked() }
    }

    /** Forgets the answer so the same link can be asked about again. */
    fun recheck() {
        checkedPackage = null
        checkLink()
    }

    fun save(channel: Channel, onSaved: () -> Unit) {
        if (!canSave) return
        saving = true
        viewModelScope.launch {
            // The gate, and the only one that cannot be walked around. Tapping
            // this button does not move focus off the link field, so the blur
            // check may never have run -- waiting on the answer here is what
            // makes the rule real rather than decorative.
            ensureChecked()
            if (blocksListing(channel, (check as? CheckState.Done)?.result)) {
                saving = false
                return@launch
            }
            // Uploaded before the listing is written, so a row never exists
            // pointing at an icon that was never sent. A failure stops the save
            // and says so, rather than quietly producing a listing with a letter
            // where its mark should be.
            val owner = account.session.value?.userId
            val artwork = iconPath?.let { path ->
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { File(path).readBytes() }.getOrNull()
                }
                if (owner != null && bytes != null) {
                    backend.uploadIcon(owner, draftId, bytes)
                } else {
                    null
                }
            } ?: iconUrl

            if (artwork == null) {
                problem = R.string.add_icon_failed
                saving = false
                return@launch
            }
            iconUrl = artwork

            repository.add(
                Listing(
                    id = draftId,
                    packageName = packageName.orEmpty(),
                    title = title.trim(),
                    category = category.trim(),
                    channel = channel,
                    optInLink = parseOptInLink(optInLink)?.href ?: optInLink.trim().ifBlank { null },
                    testNote = testNote.trim().ifBlank { null },
                    sizeBytes = parseMegabytes(sizeMb),
                    createdAt = System.currentTimeMillis(),
                    check = (check as? CheckState.Done)?.let { done ->
                        CheckRecord(
                            publicListing = when (done.result) {
                                PlayListing.Live -> true
                                PlayListing.NotPublic -> false
                                is PlayListing.Unknown -> null
                            },
                            // Nothing to compare any more: the package is read
                            // out of the link, so the two cannot disagree.
                            linkMatchesPackage = null,
                            checkedAt = done.checkedAt,
                        )
                    } ?: CheckRecord(),
                    iconPath = iconPath,
                    iconUrl = artwork,
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
                AddListingViewModel(app.listings, app.backend, app.account)
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
    copyOf: String? = null,
    viewModel: AddListingViewModel = viewModel(factory = AddListingViewModel.Factory),
) {
    // The route says which board you arrived for, not which one you leave by.
    // A live app pasted into the Testing Board is the wrong board rather than a
    // wrong link, so DevBot can move the form instead of making you retype it.
    // Keyed on the route so arriving fresh from the other tab starts over.
    var live by rememberSaveable(channel) { mutableStateOf(channel == Channel.Live) }
    val board = if (live) Channel.Live else Channel.Testing
    val testing = !live
    var linkHadFocus by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(listingId, copyOf) {
        when {
            listingId != null -> viewModel.load(context, listingId)
            copyOf != null -> viewModel.load(context, copyOf, asCopy = true)
        }
    }
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

        // Says why the button is dark rather than leaving it to be worked out.
        // A disabled control with no reason attached reads as a broken one.
        if (viewModel.iconImage == null && viewModel.iconUrl == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.add_icon_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        FormField(
            value = viewModel.title,
            onValueChange = { viewModel.title = it.take(80) },
            label = stringResource(R.string.add_app_name),
            leading = R.drawable.ic_squares_four,
        )
        Spacer(Modifier.height(10.dp))
        // Side by side, and without leading glyphs. At 155dp a 30dp icon box
        // and its padding leave about 100dp for the text -- two plain fields
        // read as a decision, six cramped ones read as a mistake.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField(
                value = viewModel.category,
                onValueChange = { viewModel.category = it.take(40) },
                label = stringResource(R.string.add_category),
                leading = null,
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = viewModel.sizeMb,
                onValueChange = { viewModel.sizeMb = it },
                label = stringResource(R.string.add_size),
                leading = null,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        FormField(
            value = viewModel.testNote,
            onValueChange = { viewModel.testNote = it.take(500) },
            label = stringResource(R.string.add_note),
            leading = R.drawable.ic_clipboard_text,
            singleLine = false,
            minLines = 2,
        )

        // Both boards need this. A live app is more certain to have a store
        // link than a closed test is, and a Live listing saved without one has
        // no package at all -- nothing to open, nothing to check, and a row
        // that can only say its category.
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
            // Asked once the field is done with, not per keystroke. The guard
            // matters: onFocusChanged also reports "not focused" on the first
            // composition, which would fire a request before anyone typed.
            // hasFocus, not isFocused: this modifier lands on FormField's outer
            // container and the focus goes to the TextField inside it, so
            // isFocused here is false even while the field is being typed in.
            // hasFocus is true when this node or any descendant holds focus.
            modifier = Modifier.onFocusChanged { state ->
                if (state.hasFocus) {
                    linkHadFocus = true
                } else if (linkHadFocus) {
                    viewModel.checkLink()
                }
            },
        )
        // Testing only: this is the address a developer pastes into their
        // closed test's tester list, and a live app has no test to paste it in.
        if (testing) {
            Spacer(Modifier.height(12.dp))
            GroupAddressCard()
        }

        // The verdict lands in two places on purpose. The panel is the record --
        // it stays put, and it is where the non-blocking answers live, since a
        // modal saying "found it" would be a nuisance. The dialog is only for
        // the one answer that refuses the save, because a refusal the person
        // can miss is the same as no refusal at all.
        val verdict = viewModel.check as? CheckState.Done
        val refused = testing && verdict?.result == PlayListing.Live
        var dialogSeen by remember(verdict) { mutableStateOf(false) }
        if (refused && !dialogSeen) {
            LiveAppDialog(
                onSwitch = {
                    live = true
                    dialogSeen = true
                },
                onDismiss = { dialogSeen = true },
            )
        }

        AnimatedVisibility(
            visible = viewModel.check != CheckState.Idle,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            CheckPanel(
                state = viewModel.check,
                testing = testing,
                onSwitchToLive = { live = true },
                onRetry = viewModel::recheck,
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            // Stays blue while it works rather than greying out. A disabled
            // button is the app saying no; this one is saying wait, and the two
            // should not look the same.
            onClick = { if (!viewModel.saving) viewModel.save(board, onDone) },
            enabled = (viewModel.canSave || viewModel.saving) &&
                !viewModel.blockedFrom(board),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (viewModel.saving) {
                Waiting(size = 22.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
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

        viewModel.problem?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
    leading: Int?,
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
        leadingIcon = leading?.let {
            // Boxed, but in neutral rather than blue. The glyph is there to
            // name the field at a glance; a tinted container per row would put
            // the accent colour on five things that cannot be interacted with.
            {
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
            }
        },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = oneDevsColors.card,
            unfocusedContainerColor = oneDevsColors.card,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
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
    val outline = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val shape = RoundedCornerShape(18.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(shape)
                .background(oneDevsColors.card)
                .clickable(onClick = onPick)
                // Drawn after the fill, not before it. background() paints in
                // modifier order, so an outline declared earlier is painted
                // over by the surface and the box simply disappears -- which
                // reads as a deliberately borderless design rather than a bug.
                .drawWithContent {
                    drawContent()
                    // Dashed while empty -- the one place an outline says
                    // something: drop an icon here. Gone once there is one.
                    if (image != null) return@drawWithContent
                    val stroke = 1.5.dp.toPx()
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
            .background(oneDevsColors.card, RoundedCornerShape(14.dp))
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

/**
 * DevBot's reading of the link, shown only once there is something to read.
 *
 * Only one answer carries an action, and it is not "fix your link": a public
 * listing means the link is right and the board is wrong, so the offer is to
 * move the form. A 404 never blocks -- a real closed test and a mistyped
 * package come back byte for byte identical, so it proves nothing either way.
 * An unreachable Play never blocks either; that would punish the worst
 * connection hardest and catch no one it was aimed at.
 */
@Composable
private fun CheckPanel(
    state: CheckState,
    testing: Boolean,
    onSwitchToLive: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = state as? CheckState.Done
    val blocking = testing && done?.result == PlayListing.Live
    val message = when (state) {
        CheckState.Idle -> return
        CheckState.Checking -> stringResource(R.string.check_working)
        is CheckState.Done -> when (state.result) {
            PlayListing.Live ->
                if (testing) {
                    stringResource(R.string.check_live_wrong_board)
                } else {
                    stringResource(R.string.check_live_confirmed)
                }
            PlayListing.NotPublic ->
                if (testing) {
                    stringResource(R.string.check_not_public_ok)
                } else {
                    stringResource(R.string.check_not_public_warn)
                }
            is PlayListing.Unknown -> stringResource(R.string.check_unreachable)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (blocking) oneDevsColors.critical.tint else oneDevsColors.card)
            .padding(12.dp),
    ) {
        DevBotMark(size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking) scheme.error else scheme.onSurfaceVariant,
            )
            val action: Pair<Int, () -> Unit>? = when {
                blocking -> R.string.check_live_switch to onSwitchToLive
                done?.result is PlayListing.Unknown -> R.string.check_retry to onRetry
                else -> null
            }
            if (action != null) {
                TextButton(
                    onClick = action.second,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(action.first),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * DevBot refusing a Testing Board listing, as a modal.
 *
 * Modal because it is the one verdict with a consequence: the save did not
 * happen, and the way out is a choice between two boards. The panel behind it
 * still carries the same message, so dismissing this loses nothing.
 */
@Composable
private fun LiveAppDialog(onSwitch: () -> Unit, onDismiss: () -> Unit) {
    // Compose dialogs do not animate in, and a verdict that simply materialises
    // reads as a glitch rather than an answer. 180ms of scale and fade is
    // enough to make it arrive without making anyone wait for it.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val entrance by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "devbotDialogEntrance",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { DevBotMark(size = 44.dp) },
        title = { Text(stringResource(R.string.check_dialog_title)) },
        text = { Text(stringResource(R.string.check_live_wrong_board)) },
        confirmButton = {
            TextButton(onClick = onSwitch) {
                Text(
                    text = stringResource(R.string.check_live_switch),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.check_dialog_change_link))
            }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.graphicsLayer {
            scaleX = 0.92f + 0.08f * entrance
            scaleY = 0.92f + 0.08f * entrance
            alpha = entrance
        },
    )
}
