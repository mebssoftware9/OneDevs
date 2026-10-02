package com.devbangs.onedevs.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Balance
import com.devbangs.onedevs.data.backend.SignInOutcome
import com.devbangs.onedevs.data.backend.signInWithGoogle
import com.devbangs.onedevs.ui.components.Waiting
import kotlinx.coroutines.launch

/**
 * Who you are, and what testing has earned you.
 *
 * Built in the language the rest of OneDevs uses: surface, a hairline border,
 * and blue reserved for the one thing here that can be pressed. The balance is
 * read from the server, because a number the phone made up is not a balance.
 *
 * There is no way to buy DevCoins and there will not be one. They are what
 * testing other developers' apps pays.
 */
@Composable
fun AccountCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()

    var userId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var avatar by remember { mutableStateOf<ImageBitmap?>(null) }

    // Read at composition, not inside the click. Resources fetched through the
    // context in a callback are not configuration-aware, so a language change
    // would leave the old string behind.
    val noGoogleMessage = stringResource(R.string.account_no_google)

    val session by app.account.session.collectAsState()
    val sharedBalance by app.account.balance.collectAsState()

    LaunchedEffect(session) {
        userId = session?.userId
        name = session?.displayName
        avatar = session?.let { loadAvatar(context, it.userId, it.photoUrl) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(com.devbangs.onedevs.ui.settings.settingsCard()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                val face = avatar
                if (face != null) {
                    Image(
                        bitmap = face,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(58.dp).clip(CircleShape),
                    )
                } else {
                    Text(
                        text = name?.trim()?.take(1)?.uppercase() ?: "\u2013",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name ?: stringResource(R.string.account_signed_out),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (userId == null) {
                        stringResource(R.string.account_sign_in_to_earn)
                    } else {
                        stringResource(R.string.account_no_apps)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(18.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_devcoins),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_devcoin),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    when (sharedBalance) {
                        Balance.Unknown, Balance.Loading -> Waiting(size = 20.dp)

                        is Balance.Known -> Text(
                            text = (sharedBalance as Balance.Known).coins.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        // The one state worth an action: the server was asked
                        // and could not answer, so offer to ask again rather
                        // than leaving a dash with no way forward.
                        Balance.Unavailable -> {
                            Text(
                                text = "\u2014",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = { app.account.retryBalance() },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.account_retry),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }

            if (userId == null) {
                Button(
                    enabled = !busy,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
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
                                is SignInOutcome.Success ->
                                    // Told once, here. The top bar is watching
                                    // the same state and updates with it.
                                    app.account.onSignedIn(outcome.session)
                                SignInOutcome.Cancelled -> Unit
                                SignInOutcome.NoAccounts ->
                                    note = noGoogleMessage
                                is SignInOutcome.Failed -> note = outcome.reason
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.account_sign_in),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                // Quieter than its opposite on purpose. Signing in is the thing
                // this card is for; signing out is a thing you should be able
                // to find, not a thing being offered.
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            app.account.signOut()
                            avatar = null
                            busy = false
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.account_sign_out),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (note != null) {
            Text(
                text = note.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            )
        }
    }
}
