package com.devbangs.onedevs.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * How many developers are testing right now, and the shape of the last day.
 *
 * [active] is null and [trend] empty until something counts them. The card
 * still draws, because the shape of it is the point -- but the LIVE pill only
 * lights when there is something live to report, and the count shows a dash
 * rather than a zero: nobody testing and nobody counting are different
 * statements, and a zero makes the wrong one.
 */
@Composable
fun LiveCard(
    active: Int?,
    trend: List<Float>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val live = oneDevsColors.live
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneDevsColors.brandTint)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active != null) live.tint else scheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (active != null) live.solid else scheme.onSurfaceVariant),
                )
                Text(
                    text = stringResource(
                        if (active != null) R.string.board_live else R.string.board_offline,
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (active != null) live.solid else scheme.onSurfaceVariant,
                )
            }
            Text(
                text = active?.let { stringResource(R.string.board_active_count, it) }
                    ?: stringResource(R.string.board_active_unknown),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.board_active_sub),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Sparkline(
                trend = trend,
                modifier = Modifier
                    .width(112.dp)
                    .height(46.dp),
            )
            Text(
                text = stringResource(R.string.board_window),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The trend, or a flat line where it would be.
 *
 * Drawn rather than charted: this is a dozen points with no axes, no labels and
 * nothing to tap, and every charting library available would arrive with all
 * three. The line is normalised to its own range, so a day that moved a little
 * fills the box as much as a day that moved a lot -- the shape is the message,
 * not the magnitude, which the number beside it already carries.
 */
@Composable
private fun Sparkline(trend: List<Float>, modifier: Modifier = Modifier) {
    val line = if (trend.size >= 2) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val points = if (trend.size >= 2) trend else listOf(0.5f, 0.5f)
        val low = points.min()
        val span = (points.max() - low).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (points.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - ((value - low) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        if (points.size >= 2) {
            val lastY = size.height - ((points.last() - low) / span) * size.height
            drawCircle(line, radius = 3.dp.toPx(), center = Offset(size.width, lastY))
        }
    }
}
