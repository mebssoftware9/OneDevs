package com.devbangs.onedevs.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import kotlinx.coroutines.launch

/**
 * Deleting the account, from inside the app, as Google Play requires.
 *
 * Says exactly what goes before asking, including the one thing it cannot
 * do: a Google Play subscription is cancelled in Google Play, not here.
 */
@Composable
fun DataGroup(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val session by app.account.session.collectAsState()
    if (session == null) return
    var asking by remember { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.settings_data), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.delete_account),
            caption = stringResource(R.string.delete_account_caption),
            onClick = { asking = true },
            icon = R.drawable.ic_trash,
        )
    }
    if (asking) DeleteAccountDialog(onDone = { asking = false })
}

@Composable
private fun DeleteAccountDialog(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val danger = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = { if (!deleting) onDone() },
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Text(
                stringResource(
                    if (failed) R.string.delete_account_failed else R.string.delete_account_body,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    scope.launch {
                        deleting = true
                        failed = false
                        // Signed out here once the server confirms, which
                        // closes this dialog along with the group around it.
                        if (!app.account.deleteAccount()) failed = true
                        deleting = false
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (deleting) R.string.delete_account_working else R.string.delete_account_confirm,
                    ),
                    color = danger,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDone) {
                Text(stringResource(R.string.delete_account_cancel))
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}
