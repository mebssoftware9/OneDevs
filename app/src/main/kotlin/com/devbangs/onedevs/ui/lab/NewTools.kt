package com.devbangs.onedevs.ui.lab

import android.content.Context
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Http
import com.devbangs.onedevs.lab.ApkIcon
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.BundleReport
import com.devbangs.onedevs.lab.Bundles
import com.devbangs.onedevs.lab.DeprecatedApis
import com.devbangs.onedevs.lab.Listing
import com.devbangs.onedevs.lab.Store
import com.devbangs.onedevs.lab.verdict
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

// The tools that were marked "soon" until the Lab could read what compiled
// code calls, open an app bundle, and fetch a policy page. Each reads the
// APK or bundle already open, so none asks for a file twice.

@Composable
internal fun DeprecatedTool(r: ApkReport) {
    CheckList(DeprecatedApis.checks(r), sorted = false)
}

@Composable
internal fun AppContentTool(r: ApkReport) {
    CheckList(Store.appContent(r), sorted = false)
}

@Composable
internal fun ContentRatingTool(r: ApkReport) {
    CheckList(Store.contentRating(r), sorted = false)
}

@Composable
internal fun NetworkTool(r: ApkReport) {
    CheckList(Store.network(r), sorted = false)
    OpenSettings(R.string.nt_open, Settings.ACTION_WIRELESS_SETTINGS)
}

@Composable
internal fun OfflineTool(r: ApkReport) {
    CheckList(Store.offline(r), sorted = false)
    OpenSettings(R.string.of_open, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
}

@Composable
internal fun LowMemoryTool(r: ApkReport) {
    CheckList(Store.lowMemory(r), sorted = false)
    OpenSettings(R.string.lm_open, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
}

@Composable
internal fun ConsistencyTool(
    r: ApkReport,
    listing: Listing,
    keywords: String,
    onListing: (Listing, String) -> Unit,
) {
    ListingInput(listing, keywords, onListing)
    if (listing.empty) {
        Empty(stringResource(R.string.aso_start))
    } else {
        CheckList(Store.consistency(r, listing), sorted = false)
    }
}

// ---- Privacy policy ------------------------------------------------------------

private const val POLICY_PREFS = "lab_listing"
private const val POLICY_KEY = "policy_url"

/** A page is kilobytes; anything past this is not the policy. */
private const val MAX_PAGE = 2L * 1024 * 1024

/** What the fetch came back with: a page, or why there is none. */
private sealed interface Fetched {
    data class Page(val page: Store.Page) : Fetched
    data class Failed(val reason: String) : Fetched
}

/**
 * Loads the policy as a reviewer's browser would: no cookies, no login,
 * redirects followed. Off the main thread, and never more than [MAX_PAGE].
 */
private suspend fun fetchPolicy(url: String): Fetched = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) OneDevs Lab")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        Http.client.newCall(request).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            val body = if (type.contains("pdf", ignoreCase = true)) "" else response.peekBody(MAX_PAGE).string()
            Fetched.Page(Store.Page(response.request.url.toString(), response.code, type, Store.text(body)))
        }
    }.getOrElse { Fetched.Failed(it.message ?: it.javaClass.simpleName) }
}

@Composable
internal fun PrivacyPolicyTool(r: ApkReport) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(POLICY_PREFS, Context.MODE_PRIVATE) }
    var url by remember { mutableStateOf(prefs.getString(POLICY_KEY, "").orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Fetched?>(null) }
    val scope = rememberCoroutineScope()

    Note(stringResource(R.string.pp_intro))
    Field(stringResource(R.string.pp_url), url, null, { url = it.trim() })
    val address = if (url.isNotEmpty() && !url.contains("://")) "https://$url" else url
    Pill(stringResource(if (checking) R.string.pp_checking else R.string.pp_check)) {
        if (checking || address.isBlank()) return@Pill
        prefs.edit { putString(POLICY_KEY, url) }
        checking = true
        scope.launch {
            result = fetchPolicy(address)
            checking = false
        }
    }
    when (val fetched = result) {
        is Fetched.Page -> CheckList(Store.privacyPolicy(r, fetched.page), sorted = false)
        is Fetched.Failed -> Note(stringResource(R.string.pp_unreachable, fetched.reason))
        null -> Unit
    }
}

// ---- App bundle -------------------------------------------------------------------

@Composable
internal fun BundleTool(
    kind: ToolKind,
    bundle: BundleReport?,
    working: Boolean,
    failure: String?,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    if (bundle == null || working) {
        Note(stringResource(R.string.bt_intro))
    }
    Pill(stringResource(if (bundle == null) R.string.bt_pick else R.string.bt_another), onPick)
    if (working) {
        Text(
            text = stringResource(R.string.bt_reading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        return
    }
    failure?.let {
        Text(
            text = stringResource(R.string.bt_failed, it),
            style = MaterialTheme.typography.bodySmall,
            color = oneDevsColors.critical.solid,
        )
    }
    val b = bundle ?: return
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    if (kind == ToolKind.BundleValidation) {
        val checks = Bundles.validation(b)
        VerdictBanner(checks.verdict(), R.string.bt_ready, R.string.bt_warn, R.string.bt_blocked)
        CheckList(checks)
        return
    }
    Facts(
        stringResource(R.string.bt_bundle),
        listOf(
            stringResource(R.string.bt_package) to b.packageName.ifEmpty { stringResource(R.string.lt_unknown) },
            stringResource(R.string.bt_version) to "${b.versionName} (${b.versionCode})",
            stringResource(R.string.bt_sdk) to "${b.minSdk} · ${b.targetSdk}",
            stringResource(R.string.bt_file) to size(b.fileBytes),
            stringResource(R.string.bt_abis) to b.abis.joinToString(", ").ifEmpty { stringResource(R.string.bt_none) },
        ),
    )
    ListCard(
        stringResource(R.string.bt_modules),
        b.modules.map { m ->
            val name = listOfNotNull(m.name, m.type).joinToString(" · ")
            "$name · ${size(m.compressedBytes)}" to stringResource(
                R.string.bt_module_parts,
                size(m.dexBytes),
                size(m.nativeBytes),
                size(m.resourceBytes),
                size(m.assetBytes),
            )
        },
    )
    Facts(
        stringResource(R.string.lt_totals),
        listOfNotNull(
            stringResource(R.string.lt_classes) to b.dex.classes.grouped(),
            stringResource(R.string.lt_fields) to b.dex.fields.grouped(),
            stringResource(R.string.lt_strings) to b.dex.strings.grouped(),
            b.compiler?.let { c ->
                stringResource(R.string.lt_compiler) to listOfNotNull(c.tool, c.version, c.mode).joinToString(" ")
            },
        ),
    )
    ListCard(stringResource(R.string.bt_permissions), b.permissions.map { it.substringAfterLast('.') to "" })
    Note(stringResource(R.string.bt_validation))
}

// ---- Play Store preview -------------------------------------------------------------

/**
 * The listing laid out the way the Play Store app lays out a phone listing:
 * icon and name, the labels Play adds, the install button, the screenshots,
 * then "About this app". Built from what the developer typed, the APK's own
 * icon, and the screenshots picked in Screenshot Preview.
 */
@Composable
internal fun StorePreviewTool(
    listing: Listing,
    icon: ApkIcon?,
    report: ApkReport?,
    screenshots: List<PickedImage>,
) {
    val scheme = MaterialTheme.colorScheme
    Note(stringResource(R.string.sp_intro))
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(oneDevsColors.card)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bitmap = icon?.full
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(oneDevsColors.well),
            ) {
                if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = listing.title.ifBlank { report?.label?.ifBlank { null } ?: stringResource(R.string.sp_untitled) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val labels = listOfNotNull(
                    report?.takeIf { Store.hasAds(it) }?.let { stringResource(R.string.sp_ads) },
                    report?.takeIf {
                        com.devbangs.onedevs.lab.CodeMarkers.BILLING in it.codeMarkers ||
                            it.requests("com.android.vending.BILLING")
                    }?.let { stringResource(R.string.sp_iap) },
                )
                if (labels.isNotEmpty()) {
                    Text(
                        text = labels.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.sp_install),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.primary)
                .padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (screenshots.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                screenshots.forEach { picked ->
                    val ratio = picked.image.width / picked.image.height.toFloat()
                    Thumb(picked, Modifier.height(200.dp).aspectRatio(ratio))
                }
            }
        }
        Text(
            text = stringResource(R.string.sp_about),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        if (listing.short.isNotBlank()) {
            Text(text = listing.short, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
        if (listing.full.isNotBlank()) {
            Text(
                text = listing.full,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (screenshots.isEmpty()) Note(stringResource(R.string.sp_no_shots))
    if (icon == null) Note(stringResource(R.string.sp_no_icon))
    if (listing.full.isNotBlank()) Note(stringResource(R.string.sp_cut))
}
