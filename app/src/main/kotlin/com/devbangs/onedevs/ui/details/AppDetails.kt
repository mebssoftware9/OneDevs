package com.devbangs.onedevs.ui.details

import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.ui.board.openPlayListing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * One app this developer filed, and everything OneDevs actually knows about it.
 *
 * Which is less than a competitor's version of this screen shows. There is no
 * tester count, no version and no download size here, because nothing measures
 * them yet -- and a number invented to fill a card is the one thing a product
 * about legitimate testing cannot afford to print.
 */
@Composable
fun AppDetailsScreen(
    listingId: String,
    onBack: () -> Unit,
    onEdit: (Listing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val listing by produceState<Listing?>(initialValue = null, listingId) {
        value = app.listings.find(listingId)
    }
    var confirmingDelete by remember { mutableStateOf(false) }

    val current = listing ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AppIcon(current)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = current.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ChannelChip(current.channel)
            }
            OverflowMenu(
                onShare = { share(context, current) },
                onEdit = { onEdit(current) },
                onDelete = { confirmingDelete = true },
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = listOfNotNull(
                current.packageName.ifBlank { null } ?: stringResource(R.string.details_no_package),
                current.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
            ).joinToString(" \u00b7 "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.details_added,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.createdAt)),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(18.dp))
        SectionCard(title = stringResource(R.string.details_instructions)) {
            Text(
                text = current.testNote ?: stringResource(R.string.details_no_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.testNote == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = stringResource(R.string.details_reports)) {
            // Not behind a paywall. These are empty because nothing has been
            // collected yet, and saying so is different from charging for it.
            ReportRow(
                icon = R.drawable.ic_chat_circle_dots,
                title = stringResource(R.string.details_feedback),
                body = stringResource(R.string.details_feedback_empty),
            )
            Spacer(Modifier.height(12.dp))
            ReportRow(
                icon = R.drawable.ic_clipboard_text,
                title = stringResource(R.string.details_production),
                body = stringResource(R.string.details_production_empty),
            )
        }

        if (current.packageName.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { openPlayListing(context, current.packageName) },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(
                    text = stringResource(R.string.details_open_play),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.launch_remove_title)) },
            text = { Text(stringResource(R.string.launch_remove_body, current.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            app.listings.remove(current.id)
                            onBack()
                        }
                    },
                ) { Text(stringResource(R.string.launch_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun AppIcon(listing: Listing, modifier: Modifier = Modifier) {
    val icon = remember(listing.iconPath) {
        listing.iconPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(16.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(60.dp).clip(shape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(60.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_squares_four),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ChannelChip(channel: Channel, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            if (channel == Channel.Live) R.string.launch_channel_live else R.string.launch_channel_testing,
        ),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun OverflowMenu(
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_dots_three_vertical),
                contentDescription = stringResource(R.string.cd_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share)) },
                onClick = { open = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_edit)) },
                onClick = { open = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun ReportRow(icon: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shares the Play link when there is one, and the app's name when there is not.
 * Nothing about OneDevs goes in the text: a developer sharing their test is
 * sharing their app, not advertising ours.
 */
private fun share(context: android.content.Context, listing: Listing) {
    val body = listing.optInLink ?: listing.packageName.ifBlank { listing.title }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, listing.title)
        putExtra(Intent.EXTRA_TEXT, "${listing.title}\n$body")
    }
    context.startActivity(Intent.createChooser(intent, listing.title))
}
