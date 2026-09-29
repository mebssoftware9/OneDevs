package com.devbangs.onedevs.ui.lab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * One layer, closed to a header and opened to its tools.
 *
 * Closed by default, all seven of them. Ninety-six tool names on one screen is
 * a wall; seven questions in the order they get asked is a path, and the order
 * is the thing worth seeing first.
 *
 * The count on the right says how many of the layer's tools run today. It is
 * the honest version of a progress bar: a layer at 0 of 13 is not broken, it
 * is waiting on something this app does not have yet, and hiding that behind a
 * tap would be worse than printing it.
 */
@Composable
fun LabLayerCard(
    layer: LabLayer,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onTool: (LabTool) -> Unit = {},
    runnable: (LabTool) -> Boolean = { false },
) {
    val accent = layer.accent(oneDevsColors)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.solid),
            ) {
                Icon(
                    painter = painterResource(layer.icon),
                    contentDescription = null,
                    tint = accent.onSolid,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${layer.number}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = accent.solid,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = stringResource(layer.name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(layer.question),
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.lab_ready, layer.available, layer.tools.size),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium,
                color = if (layer.available > 0) accent.solid else scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (layer.available > 0) accent.tint else scheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                layer.tools.forEach { tool ->
                    val ready = tool.status == LabStatus.Available
                    // Available and runnable are not the same thing. A tool
                    // can be buildable and still not built, and a row that
                    // takes a tap and does nothing is worse than one that
                    // does not take it.
                    val runs = ready && runnable(tool)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (runs) {
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onTool(tool) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 6.dp, horizontal = if (runs) 6.dp else 0.dp),
                    ) {
                        // A filled dot for a tool that runs, a ring for one
                        // that does not. Shape rather than colour alone, so
                        // the difference survives a colour-blind reader.
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .then(
                                    if (ready) {
                                        Modifier.background(accent.solid)
                                    } else {
                                        Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                                    },
                                ),
                        )
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ready) scheme.onSurface else scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            !ready -> Text(
                                text = stringResource(R.string.lab_soon),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = scheme.onSurfaceVariant,
                            )
                            runs -> Icon(
                                painter = painterResource(R.drawable.ic_caret_right),
                                contentDescription = null,
                                tint = accent.solid,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
