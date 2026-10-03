package com.devbangs.onedevs.ui.board

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R

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
    // The brand's own sky, the same in both themes: the one card on the Board
    // that is about the platform rather than an app, so it should not look
    // like the rows beneath it.
    val shape = RoundedCornerShape(24.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = SkyGlow, spotColor = SkyGlow)
            .clip(shape)
            .background(Brush.linearGradient(listOf(SkyTop, SkyBottom)))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(SkyGlow.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width, 0f),
                )
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            LivePill(active != null)
            Text(
                text = active?.let { pluralStringResource(R.plurals.board_active_count, it, it) }
                    ?: stringResource(R.string.board_active_unknown),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.board_active_sub),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Sparkline(
                trend = trend,
                modifier = Modifier
                    .width(118.dp)
                    .height(50.dp),
            )
            Text(
                text = stringResource(R.string.board_window),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val SkyTop = Color(0xFF0A1C8F)
private val SkyBottom = Color(0xFF04115E)
private val SkyGlow = Color(0xFF1048FF)
private val Signal = Color(0xFF5CF4FF)

/** LIVE, with a dot that breathes while there is something live to report. */
@Composable
private fun LivePill(live: Boolean) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (live) Signal.copy(alpha = pulse) else Color.White.copy(alpha = 0.5f)),
        )
        Text(
            text = stringResource(if (live) R.string.board_live else R.string.board_offline),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
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
    val line = if (trend.size >= 2) Signal else Color.White.copy(alpha = 0.3f)
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
        if (points.size >= 2 && trend.size >= 2) {
            // The area under the line, fading to nothing.
            val area = androidx.compose.ui.graphics.Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        if (points.size >= 2) {
            val lastY = size.height - ((points.last() - low) / span) * size.height
            drawCircle(line, radius = 3.dp.toPx(), center = Offset(size.width, lastY))
        }
    }
}
