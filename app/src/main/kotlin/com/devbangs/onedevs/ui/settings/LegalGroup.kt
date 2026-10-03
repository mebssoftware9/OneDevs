package com.devbangs.onedevs.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.devbangs.onedevs.ads.Ads
import com.devbangs.onedevs.ui.plans.findActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.devbangs.onedevs.R

/** Where OneDevs' policies live. The same pages the Play listing links to. */
object LegalLinks {
    const val PRIVACY = "https://mebs.app/privacy/onedevs"
    const val TERMS = "https://mebs.app/terms/onedevs"
    const val REFUND = "https://mebs.app/refund/onedevs"
}

/** The policies, each opening in the browser. */
@Composable
fun LegalGroup(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val privacyChoices by Ads.privacyChoices.collectAsState()
    SettingsGroup(title = stringResource(R.string.settings_legal), modifier = modifier) {
        SettingsRow(
            label = stringResource(R.string.legal_privacy),
            onClick = { uri.openUri(LegalLinks.PRIVACY) },
            icon = R.drawable.ic_shield_check,
        )
        SettingsRow(
            label = stringResource(R.string.legal_terms),
            onClick = { uri.openUri(LegalLinks.TERMS) },
            icon = R.drawable.ic_file_text,
        )
        SettingsRow(
            label = stringResource(R.string.legal_refund),
            onClick = { uri.openUri(LegalLinks.REFUND) },
            icon = R.drawable.ic_receipt,
        )
        // Where the law gives the right to change ad consent (the EEA, the UK,
        // Switzerland, US states with privacy laws), it is one tap away.
        if (privacyChoices) {
            SettingsRow(
                label = stringResource(R.string.legal_privacy_choices),
                caption = stringResource(R.string.legal_privacy_choices_caption),
                onClick = { context.findActivity()?.let(Ads::showPrivacyChoices) },
                icon = R.drawable.ic_lock,
            )
        }
    }
}
