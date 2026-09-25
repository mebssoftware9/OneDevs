package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Tall enough to tap without being a control in its own right. It sits on the
 * same line as a 16sp heading, and a 48dp pill beside that reads as the louder
 * of the two.
 */
private val FilterHeight = 36.dp

/**
 * A row of mutually exclusive filters sharing one pill.
 *
 * Not SingleChoiceSegmentedButtonRow. That draws outlined buttons with a check
 * mark inside the selected one, which is a lot of chrome for three words and
 * looks like nothing else in this app. This is the same brand tint the top bar
 * uses, with the selected segment filled in primary.
 *
 * [fillWidth] makes the segments share the width instead of sizing to their
 * labels -- two categories across a screen, rather than three chips beside a
 * heading. [icons] is optional for the same reason: a pair of top-level
 * categories earns a glyph, three chips narrowing a list do not.
 */
@Composable
fun FilterPills(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<Int>? = null,
    fillWidth: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(FilterHeight)
            .clip(CircleShape)
            .background(oneDevsColors.brandTint)
            .padding(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val ink = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp),
            ) {
                icons?.getOrNull(index)?.let { glyph ->
                    Icon(
                        painter = painterResource(glyph),
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier
                            .size(15.dp)
                            .padding(end = 0.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = ink,
                )
            }
        }
    }
}
