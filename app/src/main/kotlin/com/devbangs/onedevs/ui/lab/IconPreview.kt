package com.devbangs.onedevs.ui.lab

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkIcon
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.str

// Layer six's one local tool. The listing itself lives in Play Console and is
// out of reach without scraping the store; the launcher icon is inside the
// APK, so it can be shown the way launchers will show it.

/** An adaptive icon's layers are 108dp with the middle 72dp visible. */
private const val LAYER_SCALE = 108f / 72f

/** The shapes launchers mask adaptive icons to, from round to square. */
private val SHAPES: List<Shape> = listOf(
    CircleShape,
    RoundedCornerShape(percent = 35),
    RoundedCornerShape(percent = 22),
    RoundedCornerShape(percent = 6),
)

@Composable
internal fun IconTool(icon: ApkIcon?) {
    if (icon == null) {
        Empty(stringResource(R.string.lt_icon_none))
        return
    }
    CheckList(
        buildList {
            add(
                if (icon.adaptive) {
                    Check(Status.Pass, str(R.string.ck_icon_adaptive))
                } else {
                    Check(Status.Warn, str(R.string.ck_icon_legacy), str(R.string.ck_icon_legacy_d))
                },
            )
            when {
                !icon.adaptive -> Unit
                !icon.monochromeKnown -> add(Check(Status.Info, str(R.string.ck_icon_mono_unknown)))
                icon.monochrome != null -> add(Check(Status.Pass, str(R.string.ck_icon_mono)))
                else -> add(Check(Status.Warn, str(R.string.ck_icon_no_mono), str(R.string.ck_icon_no_mono_d)))
            }
        },
    )

    SectionTitle(stringResource(R.string.lt_icon_shapes))
    IconRow { SHAPES.forEach { LauncherIcon(icon, 56.dp, it) } }
    IconRow { listOf(24.dp, 36.dp, 48.dp, 72.dp).forEach { LauncherIcon(icon, it, CircleShape) } }

    val foreground = icon.foreground
    val background = icon.background
    if (icon.adaptive && foreground != null && background != null) {
        SectionTitle(stringResource(R.string.lt_icon_layers))
        IconRow {
            Layer(stringResource(R.string.lt_icon_fg), foreground)
            Layer(stringResource(R.string.lt_icon_bg), background)
            icon.monochrome?.let { Layer(stringResource(R.string.lt_icon_mono), it, tinted = true) }
        }
    }
    icon.monochrome?.let { mono ->
        SectionTitle(stringResource(R.string.lt_icon_themed))
        Themed(mono)
    }
    Note(stringResource(R.string.lt_icon_play))
}

@Composable
private fun IconRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
    ) { content() }
}

/**
 * The icon as a launcher draws it: an adaptive icon's two layers, oversized
 * and masked to [shape]; a legacy icon as the bitmap it is.
 */
@Composable
private fun LauncherIcon(icon: ApkIcon, size: Dp, shape: Shape) {
    val foreground = icon.foreground
    val background = icon.background
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(shape),
    ) {
        if (icon.adaptive && foreground != null && background != null) {
            val bg = remember(background) { background.asImageBitmap() }
            val fg = remember(foreground) { foreground.asImageBitmap() }
            Image(bitmap = bg, contentDescription = null, modifier = Modifier.requiredSize(size * LAYER_SCALE))
            Image(bitmap = fg, contentDescription = null, modifier = Modifier.requiredSize(size * LAYER_SCALE))
        } else {
            val full = remember(icon.full) { icon.full.asImageBitmap() }
            Image(bitmap = full, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

@Composable
private fun Layer(label: String, bitmap: Bitmap, tinted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            colorFilter = if (tinted) ColorFilter.tint(scheme.onSurface) else null,
            modifier = Modifier
                .size(64.dp)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A themed icon: the monochrome layer in the wallpaper's colours. */
@Composable
private fun Themed(mono: Bitmap) {
    val scheme = MaterialTheme.colorScheme
    val image = remember(mono) { mono.asImageBitmap() }
    IconRow {
        listOf(
            scheme.primaryContainer to scheme.onPrimaryContainer,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
        ).forEach { (plate, ink) ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(plate),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(ink),
                    modifier = Modifier.requiredSize(56.dp * LAYER_SCALE),
                )
            }
        }
    }
}
