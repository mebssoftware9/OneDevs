package com.devbangs.onedevs.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors

/** Where a row's words start: past the 16dp edge, the 30dp mark and the 12dp gap. */
private val RowTextStart = 58.dp

/**
 * A titled group of settings rows, drawn as one hairline card.
 *
 * Grouping is the whole point of a settings screen: twenty rows in one list is
 * a list, and the same twenty under four headings is a place you can find
 * something in. The heading sits outside the card, in the page's own colour,
 * so it reads as a label for the card rather than a row inside it.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
            content = content,
        )
    }
}

/**
 * One row. [value] is what the setting currently is, shown on the right in the
 * brand colour when it can be changed and in the muted one when it is simply a
 * fact about the device. [icon] sits in a small tile of its [accent], so each
 * row can be found by its mark before its words are read.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    readOnly: Boolean = false,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: Int? = null,
    accent: Accent? = null,
    detail: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (icon != null) {
                val tone = accent ?: oneDevsColors.testing
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tone.tint),
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = tone.solid,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (readOnly) scheme.onSurfaceVariant else scheme.primary,
                )
            }
            if (onClick != null && !readOnly) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(
                        if (detail != null && expanded) R.drawable.ic_caret_up else R.drawable.ic_caret_right,
                    ),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (detail != null) {
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 6.dp), content = detail)
            }
        }
    }
}

/**
 * A hairline between rows, inset to where the words start so it reads as a
 * separator and not a border.
 */
@Composable
fun SettingsDivider(inset: Dp = RowTextStart) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * One option inside an expanded row. The chosen one is marked rather than
 * merely coloured, because colour alone is not a state anyone can rely on.
 * [leading] is a short mark before the words, such as a flag.
 */
@Composable
fun SettingsChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = RowTextStart, end = 16.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (leading != null) {
                Text(text = leading, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = if (selected) scheme.primary else scheme.onSurface,
            )
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(oneDevsColors.brandTint),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}
