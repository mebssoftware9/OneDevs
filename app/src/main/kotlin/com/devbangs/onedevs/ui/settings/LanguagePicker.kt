package com.devbangs.onedevs.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.settings.AppLanguage
import com.devbangs.onedevs.settings.AppLocale
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Choosing the app's language.
 *
 * Each language names itself -- English, Español -- and is never translated.
 * Someone looking for their own language is looking for the word they would
 * write, not for whatever the language they cannot read calls it. Only "system
 * default" is translated, because it is not a language.
 *
 * On Android 13 and above the platform also offers this in system settings,
 * and both read the same value, so the two can never disagree.
 */
@Composable
fun LanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLocale.current(context)) }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        )
        AppLanguage.entries.forEach { language ->
            val selected = language == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        AppLocale.set(context, language)
                        current = language
                        // Below 13 the locale is only read when a context is
                        // built, so nothing already on screen changes until
                        // the activity is rebuilt. The platform does this
                        // itself from 13 up.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            (context as? Activity)?.recreate()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(
                    text = if (language == AppLanguage.System) {
                        stringResource(R.string.settings_language_system)
                    } else {
                        language.label
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
                if (selected) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(oneDevsColors.brandTint),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
