package com.devbangs.onedevs.ui.lab

import com.devbangs.onedevs.ui.theme.oneDevsColors
import androidx.compose.foundation.background
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.AssetSlot
import com.devbangs.onedevs.lab.Aso
import com.devbangs.onedevs.lab.Listing
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.StoreAssets
import com.devbangs.onedevs.lab.StoreImage
import com.devbangs.onedevs.lab.verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Layers six and seven: the listing. Nothing here needs Play's data -- the
// graphics are files on this phone and the text is what the developer types --
// so every rule runs here, against Play Console's own limits.

/** A picked image: what the rules read, and a small copy to show. */
internal class PickedImage(val image: StoreImage, val preview: Bitmap?)

/** Longest side of the on-screen copy. The rules read the file's real size. */
private const val PREVIEW_PX = 480

/**
 * Reads an image's real dimensions, format and size without holding the
 * full-size bitmap: a 3840px screenshot is 59 MB decoded.
 */
internal fun loadImage(context: Context, uri: Uri): PickedImage? = runCatching {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment.orEmpty()
    var bytes = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) bytes = c.getLong(1)
        }
    }
    val head = resolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArray(StoreAssets.HEAD_BYTES)
        var n = 0
        while (n < buffer.size) {
            val read = stream.read(buffer, n, buffer.size - n)
            if (read <= 0) break
            n += read
        }
        buffer.copyOf(n)
    } ?: return@runCatching null
    if (bytes < 0) {
        bytes = resolver.openInputStream(uri)?.use { stream ->
            val skip = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(skip)
                if (read <= 0) break
                total += read
            }
            total
        } ?: 0
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_PX) sample *= 2
    val preview = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    PickedImage(
        StoreImage(
            name = name,
            width = bounds.outWidth,
            height = bounds.outHeight,
            bytes = bytes,
            mime = StoreAssets.sniff(head),
            alpha = StoreAssets.pngAlpha(head),
        ),
        preview,
    )
}.getOrNull()

/** A picker for one image or several, and the loading that follows it. */
@Composable
private fun ImagePicker(label: Int, multiple: Boolean, onImages: (List<PickedImage>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    fun load(uris: List<Uri>) {
        if (uris.isEmpty()) return
        loading = true
        scope.launch {
            val images = withContext(Dispatchers.IO) { uris.mapNotNull { loadImage(context, it) } }
            loading = false
            onImages(images)
        }
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { load(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> load(listOfNotNull(uri)) }
    Pill(stringResource(if (loading) R.string.as_loading else label)) {
        if (loading) return@Pill
        if (multiple) many.launch("image/*") else one.launch("image/*")
    }
}

@Composable
private fun Thumb(picked: PickedImage, modifier: Modifier = Modifier) {
    val bitmap = picked.preview ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = picked.image.name,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(oneDevsColors.well),
    )
}

@Composable
private fun slotName(slot: AssetSlot): String = stringResource(
    when (slot) {
        AssetSlot.Icon -> R.string.as_slot_icon
        AssetSlot.FeatureGraphic -> R.string.as_slot_feature
        AssetSlot.Screenshot -> R.string.as_slot_screenshot
    },
)

/** Store Asset Validation: any mix of listing graphics, each checked for the slot it fits. */
@Composable
internal fun StoreAssetsTool(images: List<PickedImage>, onImages: (List<PickedImage>) -> Unit) {
    Note(stringResource(R.string.as_intro))
    ImagePicker(R.string.as_pick_many, multiple = true, onImages = onImages)
    if (images.isEmpty()) return
    val shots = images.filter { StoreAssets.guess(it.image) == AssetSlot.Screenshot }
    val all = images.flatMap { StoreAssets.check(StoreAssets.guess(it.image), it.image) } +
        if (shots.isNotEmpty()) StoreAssets.screenshotSet(shots.map { it.image }) else emptyList()
    VerdictBanner(all.verdict(), R.string.as_ready, R.string.as_warn, R.string.as_blocked)
    images.forEach { picked ->
        val slot = StoreAssets.guess(picked.image)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(picked, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = picked.image.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${slotName(slot)} · ${picked.image.width} × ${picked.image.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CheckList(StoreAssets.check(slot, picked.image))
    }
    if (shots.isNotEmpty()) {
        SectionTitle(stringResource(R.string.as_set))
        CheckList(StoreAssets.screenshotSet(shots.map { it.image }))
    }
}

@Composable
internal fun FeatureGraphicTool(images: List<PickedImage>, onImages: (List<PickedImage>) -> Unit) {
    Note(stringResource(R.string.as_feature_intro))
    ImagePicker(R.string.as_pick_feature, multiple = false, onImages = onImages)
    val picked = images.firstOrNull() ?: return
    Thumb(picked, Modifier.fillMaxWidth().aspectRatio(StoreAssets.FEATURE_WIDTH / StoreAssets.FEATURE_HEIGHT.toFloat()))
    val checks = StoreAssets.featureGraphic(picked.image)
    VerdictBanner(checks.verdict(), R.string.as_ready, R.string.as_warn, R.string.as_blocked)
    CheckList(checks)
}

/** Screenshot Preview: the set in a row at the height Play shows it, then the rules. */
@Composable
internal fun ScreenshotsTool(images: List<PickedImage>, onImages: (List<PickedImage>) -> Unit) {
    Note(stringResource(R.string.as_shots_intro))
    ImagePicker(R.string.as_pick_shots, multiple = true, onImages = onImages)
    if (images.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        images.forEach { picked ->
            val ratio = picked.image.width / picked.image.height.toFloat()
            Thumb(picked, Modifier.height(220.dp).aspectRatio(ratio))
        }
    }
    val set = StoreAssets.screenshotSet(images.map { it.image })
    val each = images.flatMap { StoreAssets.screenshot(it.image) }
    VerdictBanner((set + each).verdict(), R.string.as_ready, R.string.as_warn, R.string.as_blocked)
    CheckList(set)
    images.forEach { picked ->
        val problems = StoreAssets.screenshot(picked.image).filter { it.status != Status.Pass }
        if (problems.isNotEmpty()) {
            SectionTitle(picked.image.name)
            CheckList(problems)
        }
    }
}

// ---- Listing text ------------------------------------------------------------

private const val LISTING_PREFS = "lab_listing"

/** The listing a developer typed, kept on the phone so every text tool reads the same draft. */
internal object ListingDraft {
    fun load(context: Context): Listing {
        val p = context.getSharedPreferences(LISTING_PREFS, Context.MODE_PRIVATE)
        return Listing(
            title = p.getString("title", "").orEmpty(),
            short = p.getString("short", "").orEmpty(),
            full = p.getString("full", "").orEmpty(),
            keywords = Aso.keywords(p.getString("keywords", "").orEmpty()),
        )
    }

    fun save(context: Context, listing: Listing, keywords: String) {
        context.getSharedPreferences(LISTING_PREFS, Context.MODE_PRIVATE).edit {
            putString("title", listing.title)
            putString("short", listing.short)
            putString("full", listing.full)
            putString("keywords", keywords)
        }
    }

    fun keywordText(context: Context): String =
        context.getSharedPreferences(LISTING_PREFS, Context.MODE_PRIVATE).getString("keywords", "").orEmpty()
}

@Composable
private fun Field(
    label: String,
    value: String,
    max: Int?,
    onValue: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val count = value.codePointCount(0, value.length)
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 8,
        isError = max != null && count > max,
        supportingText = max?.let { { Text("$count / $it") } },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = oneDevsColors.card,
            unfocusedContainerColor = oneDevsColors.card,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The four fields every listing tool reads. Typing in one tool fills them all. */
@Composable
internal fun ListingInput(listing: Listing, keywords: String, onChange: (Listing, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Field(stringResource(R.string.aso_f_title), listing.title, Aso.TITLE_MAX, { onChange(listing.copy(title = it), keywords) })
        Field(stringResource(R.string.aso_f_short), listing.short, Aso.SHORT_MAX, { onChange(listing.copy(short = it), keywords) })
        Field(
            stringResource(R.string.aso_f_full),
            listing.full,
            Aso.FULL_MAX,
            { onChange(listing.copy(full = it), keywords) },
            singleLine = false,
            minLines = 4,
        )
        Field(
            stringResource(R.string.aso_f_keywords),
            keywords,
            null,
            { onChange(listing.copy(keywords = Aso.keywords(it)), it) },
        )
        Note(stringResource(R.string.aso_keywords_hint))
    }
}

@Composable
internal fun ListingTool(kind: ToolKind, listing: Listing) {
    if (listing.empty) {
        Empty(stringResource(R.string.aso_start))
        return
    }
    when (kind) {
        ToolKind.Title -> CheckList(Aso.title(listing))
        ToolKind.ShortDescription -> CheckList(Aso.short(listing))
        ToolKind.LongDescription -> {
            Facts(
                stringResource(R.string.aso_f_full),
                listOf(
                    stringResource(R.string.aso_chars) to listing.full.codePointCount(0, listing.full.length).grouped(),
                    stringResource(R.string.aso_words) to Aso.words(listing.full).size.grouped(),
                    stringResource(R.string.aso_paragraphs) to
                        listing.full.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }.grouped(),
                ),
            )
            CheckList(Aso.full(listing))
        }
        ToolKind.KeywordPlacement -> PlacementView(listing)
        ToolKind.KeywordCoverage -> {
            if (listing.keywords.isNotEmpty()) {
                Facts(
                    stringResource(R.string.aso_coverage),
                    listOf(stringResource(R.string.aso_coverage_found) to "${Aso.coverage(listing)}%"),
                )
            }
            CheckList(Aso.coverageChecks(listing))
        }
        ToolKind.Metadata -> CheckList(Aso.suggestions(listing), sorted = false)
        ToolKind.AsoScore -> ScoreView(listing)
        ToolKind.ListingQuality -> CheckList(Aso.quality(listing))
        ToolKind.ListingChecklist -> CheckList(Aso.checklist(listing), sorted = false)
        else -> Unit
    }
}

@Composable
private fun PlacementView(listing: Listing) {
    if (listing.keywords.isEmpty()) {
        Empty(stringResource(R.string.aso_no_keywords))
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val yes = stringResource(R.string.lt_yes)
    val no = stringResource(R.string.lt_no)
    ListCard(
        stringResource(R.string.aso_placement),
        Aso.placements(listing).map { p ->
            p.keyword to listOf(
                "${stringResource(R.string.aso_f_title)}: ${if (p.inTitle) yes else no}",
                "${stringResource(R.string.aso_f_short)}: ${if (p.inShort) yes else no}",
                "${stringResource(R.string.aso_fold)}: ${if (p.aboveFold) yes else no}",
                stringResource(R.string.aso_in_full, p.inFull.grouped(), "%.1f".format(locale, p.density)),
            ).joinToString(" · ")
        },
    )
    Note(stringResource(R.string.aso_placement_d))
}

@Composable
private fun ScoreView(listing: Listing) {
    val parts = Aso.score(listing)
    val total = Aso.total(parts)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "$total",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.aso_score_of),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Facts(
        stringResource(R.string.aso_score_parts),
        parts.map { stringResource(it.label) to "${it.points} / ${it.max}" },
    )
    SectionTitle(stringResource(R.string.aso_score_next))
    CheckList(Aso.suggestions(listing), sorted = false)
    Note(stringResource(R.string.aso_score_d))
}
