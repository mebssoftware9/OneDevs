package com.devbangs.onedevs.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.SignInOutcome
import com.devbangs.onedevs.data.backend.signInWithGoogle
import com.devbangs.onedevs.ui.components.Waiting
import com.devbangs.onedevs.ui.Wordmark
import kotlinx.coroutines.launch

/**
 * The door.
 *
 * Everything behind it belongs to an account: DevCoins are earned and spent,
 * missions are joined, listings are owned. A signed-out visitor browsing a
 * board of apps they cannot test would be the app describing itself falsely,
 * so there is nothing to browse until there is someone to browse as.
 */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val noGoogleMessage = stringResource(R.string.account_no_google)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp),
    ) {
        Wordmark()

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.signin_headline),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.signin_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Button(
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                scope.launch {
                    busy = true
                    note = null
                    val outcome = signInWithGoogle(
                        context = context,
                        backend = app.backend,
                        webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                    )
                    when (outcome) {
                        is SignInOutcome.Success -> app.account.onSignedIn(outcome.session)
                        SignInOutcome.Cancelled -> Unit
                        SignInOutcome.NoAccounts -> note = noGoogleMessage
                        is SignInOutcome.Failed -> note = outcome.reason
                    }
                    busy = false
                }
            },
        ) {
            if (busy) {
                Waiting(size = 22.dp)
            } else {
                Text(
                    text = stringResource(R.string.signin_cta),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.signin_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )

        if (note != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = note.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
