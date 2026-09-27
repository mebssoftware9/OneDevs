package com.devbangs.onedevs.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.R
import com.devbangs.onedevs.settings.AppLanguage
import com.devbangs.onedevs.settings.AppLocale
import com.devbangs.onedevs.settings.AppTheme
import com.devbangs.onedevs.settings.ThemeStore
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Who this is, as far as the app can honestly say.
 *
 * There are no accounts yet, so there is no name to show and no avatar to draw.
 * Saying so is better than a placeholder silhouette that implies one exists
 * and failed to load.
 */
@Composable
fun ProfileHeader(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneDevsColors.brandTint)
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(oneDevsColors.brandNavy),
        ) { DevBotMark() }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(
                text = stringResource(R.string.profile_signed_out),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.profile_signed_out_body),
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Language, theme, and the way out to the system's own notification settings. */
@Composable
fun AppSettingsGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(AppLocale.current(context)) }
    SettingsGroup(title = stringResource(R.string.settings_app), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.settings_language),
            value = if (language == AppLanguage.System) {
                stringResource(R.string.settings_language_system)
            } else {
                language.label
            },
            expanded = open == "language",
            onClick = { open = if (open == "language") null else "language" },
            detail = {
                AppLanguage.entries.forEach { choice ->
                    SettingsChoice(
                        label = if (choice == AppLanguage.System) {
                            stringResource(R.string.settings_language_system)
                        } else {
                            choice.label
                        },
                        selected = choice == language,
                        onClick = {
                            AppLocale.set(context, choice)
                            language = choice
                            // Below 13 a locale is only read when a context is
                            // built, so nothing on screen changes until the
                            // activity is. The platform handles it from 13 up.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                    )
                }
            },
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_theme),
            value = stringResource(ThemeStore.current.label),
            expanded = open == "theme",
            onClick = { open = if (open == "theme") null else "theme" },
            detail = {
                AppTheme.entries.forEach { choice ->
                    SettingsChoice(
                        label = stringResource(choice.label),
                        selected = choice == ThemeStore.current,
                        onClick = { ThemeStore.set(context, choice) },
                    )
                }
            },
        )
        SettingsDivider()
        // The system owns notification settings, and it owns them better: per
        // channel, with Do Not Disturb and importance in one place. Rebuilding
        // that here would be a second set of switches that can disagree with
        // the first.
        SettingsRow(
            label = stringResource(R.string.settings_notifications),
            onClick = { openNotificationSettings(context) },
        )
    }
}

private val AppTheme.label: Int
    get() = when (this) {
        AppTheme.System -> R.string.theme_system
        AppTheme.Light -> R.string.theme_light
        AppTheme.Dark -> R.string.theme_dark
    }

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * What this phone is, read from Build.
 *
 * It costs nothing and needs no permission, and on a platform whose whole
 * subject is testing on real devices it is the first thing anyone reporting a
 * problem will be asked for.
 */
@Composable
fun DeviceGroup(modifier: Modifier = Modifier) {
    SettingsGroup(title = stringResource(R.string.settings_device), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.device_model),
            value = "${Build.MANUFACTURER} ${Build.MODEL}",
            readOnly = true,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.device_android),
            value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            readOnly = true,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.device_abi),
            value = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            readOnly = true,
        )
    }
}

/**
 * Version and third-party notices.
 *
 * The notices are not a courtesy. The OFL requires the font's notice to travel
 * with it and MIT requires its own in all copies, so an app that sets every
 * word in Geist and draws its glyphs from Phosphor owes both of them somewhere
 * a user can actually reach.
 */
@Composable
fun AboutGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val notices = remember {
        runCatching {
            context.assets.open("licenses.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    SettingsGroup(title = stringResource(R.string.settings_about), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.about_version),
            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            readOnly = true,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.about_licenses),
            expanded = open,
            onClick = { open = !open },
            detail = {
                Text(
                    text = notices,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            },
        )
    }
}
