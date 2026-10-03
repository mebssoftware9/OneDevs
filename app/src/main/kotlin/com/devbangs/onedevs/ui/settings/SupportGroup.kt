package com.devbangs.onedevs.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.R

/** Who answers, and where. The plans promise support; this is where it is. */
object Support {
    const val EMAIL = "mebssoftware9@gmail.com"
}

/** Help & support: write to the studio, and who the studio is. */
@Composable
fun SupportGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val subject = stringResource(R.string.support_subject, BuildConfig.VERSION_NAME)
    SettingsGroup(title = stringResource(R.string.settings_support), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.support_contact),
            value = Support.EMAIL,
            onClick = { writeToSupport(context, subject) },
            icon = R.drawable.ic_envelope_simple,
        )
        SettingsRow(
            label = stringResource(R.string.support_studio),
            value = stringResource(R.string.studio_name),
            readOnly = true,
            icon = R.drawable.ic_buildings,
        )
    }
}

/** Opens the mail app addressed to support, the version already in the subject. */
private fun writeToSupport(context: Context, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No mail app: the address is on the row, readable and copyable.
    }
}
