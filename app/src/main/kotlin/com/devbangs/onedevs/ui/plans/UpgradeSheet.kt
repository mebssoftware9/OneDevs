package com.devbangs.onedevs.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * What a free Lab says when someone reaches for a second app.
 *
 * Not a wall: the app they have stays exactly where it was, the sheet says
 * so first, and "Not now" puts them back in the Lab with nothing lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeSheet(labApp: String, onUpgrade: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(oneDevsColors.testing.tint),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flask_fill),
                    contentDescription = null,
                    tint = oneDevsColors.testing.solid,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = stringResource(R.string.upgrade_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.upgrade_body, labApp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Feature(stringResource(R.string.plans_pro_1), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                Feature(stringResource(R.string.plans_pro_2), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                Feature(stringResource(R.string.upgrade_ghostline), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
            }
            ActionButton(
                text = stringResource(R.string.upgrade_see_pro),
                enabled = true,
                filled = true,
                onClick = onUpgrade,
            )
            ActionButton(
                text = stringResource(R.string.upgrade_not_now),
                enabled = true,
                filled = false,
                onClick = onDismiss,
            )
        }
    }
}
