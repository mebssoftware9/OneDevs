package com.devbangs.onedevs.ads

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * The one ad in the app: a native ad shaped like the Board's own cards.
 *
 * Nothing is drawn until consent allows ads and an ad has loaded, so there is
 * never an empty box where an ad might have been. It carries what Google's
 * native policy requires: the "Ad" label, the advertiser's headline, the
 * AdChoices mark (placed by the SDK) and the advertiser's own button.
 */
@Composable
fun NativeAdCard(modifier: Modifier = Modifier) {
    val unit = Ads.nativeUnit ?: return
    val ready by Ads.ready.collectAsState()
    if (!ready) return
    val context = LocalContext.current
    var ad by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(unit) {
        var alive = true
        AdLoader.Builder(context, unit)
            .forNativeAd { loaded ->
                if (alive) ad = loaded else loaded.destroy()
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No fill is ordinary: the card simply is not there.
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build(),
            )
            .build()
            .loadAd(AdRequest.Builder().build())
        onDispose {
            alive = false
            ad?.destroy()
        }
    }

    val loaded = ad ?: return
    val colors = AdColors(
        card = oneDevsColors.card.toArgb(),
        well = oneDevsColors.well.toArgb(),
        ink = MaterialTheme.colorScheme.onSurface.toArgb(),
        muted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        accent = MaterialTheme.colorScheme.primary.toArgb(),
        onAccent = MaterialTheme.colorScheme.onPrimary.toArgb(),
        badge = oneDevsColors.caution.solid.toArgb(),
        onBadge = oneDevsColors.caution.onSolid.toArgb(),
    )
    val label = stringResource(R.string.ad_label)
    AndroidView(
        factory = { NativeAdLayout(it) },
        update = { it.bind(loaded, colors, label) },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(colors.card)),
    )
}

private data class AdColors(
    val card: Int,
    val well: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val badge: Int,
    val onBadge: Int,
)

/** A NativeAdView laid out like a Board card: icon, headline, body, media, button. */
private class NativeAdLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NativeAdView(context, attrs) {
    private fun px(dp: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private val iconImage = ImageView(context)
    private val badgeText = TextView(context)
    private val titleText = TextView(context)
    private val advertiserText = TextView(context)
    private val bodyLine = TextView(context)
    private val mediaFrame = MediaView(context)
    private val actionButton = Button(context)

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(14))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconImage.layoutParams = LinearLayout.LayoutParams(px(44), px(44))
        iconImage.clipToOutline = true
        iconImage.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(12)
                marginEnd = px(24)
            }
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badgeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        badgeText.typeface = Typeface.DEFAULT_BOLD
        badgeText.setPadding(px(6), px(1), px(6), px(1))
        advertiserText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        advertiserText.maxLines = 1
        advertiserText.setPadding(px(6), 0, 0, 0)
        line.addView(badgeText)
        line.addView(advertiserText)
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        titleText.typeface = Typeface.DEFAULT_BOLD
        titleText.maxLines = 2
        names.addView(titleText)
        names.addView(line)
        top.addView(iconImage)
        top.addView(names)

        bodyLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        bodyLine.maxLines = 3
        bodyLine.setPadding(0, px(10), 0, 0)

        mediaFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(180)).apply {
            topMargin = px(10)
        }
        mediaFrame.clipToOutline = true

        actionButton.isAllCaps = false
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        actionButton.typeface = Typeface.DEFAULT_BOLD
        actionButton.stateListAnimator = null
        actionButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply {
            topMargin = px(12)
        }

        column.addView(top)
        column.addView(bodyLine)
        column.addView(mediaFrame)
        column.addView(actionButton)
        addView(column)

        iconView = iconImage
        headlineView = titleText
        advertiserView = advertiserText
        bodyView = bodyLine
        mediaView = mediaFrame
        callToActionView = actionButton
    }

    fun bind(ad: NativeAd, colors: AdColors, label: String) {
        badgeText.text = label
        badgeText.setTextColor(colors.onBadge)
        badgeText.background = rounded(colors.badge, px(6).toFloat())
        titleText.text = ad.headline
        titleText.setTextColor(colors.ink)
        advertiserText.text = ad.advertiser.orEmpty()
        advertiserText.setTextColor(colors.muted)
        advertiserText.visibility = if (ad.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
        bodyLine.text = ad.body.orEmpty()
        bodyLine.setTextColor(colors.muted)
        bodyLine.visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
        val drawable = ad.icon?.drawable
        iconImage.setImageDrawable(drawable)
        iconImage.background = rounded(colors.well, px(12).toFloat())
        iconImage.visibility = if (drawable == null) View.GONE else View.VISIBLE
        mediaFrame.background = rounded(colors.well, px(14).toFloat())
        mediaFrame.visibility = if (ad.mediaContent?.hasVideoContent() == true || ad.images.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        actionButton.text = ad.callToAction.orEmpty()
        actionButton.setTextColor(colors.onAccent)
        actionButton.background = rounded(colors.accent, px(22).toFloat())
        actionButton.visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        setNativeAd(ad)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
}
