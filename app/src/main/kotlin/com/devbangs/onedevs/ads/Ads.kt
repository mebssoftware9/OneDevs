package com.devbangs.onedevs.ads

import android.app.Activity
import com.devbangs.onedevs.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Consent first, ads second.
 *
 * Google's User Messaging Platform is the consent manager: it knows where
 * the person is and which law applies -- GDPR in the EEA, the UK and
 * Switzerland, the US state privacy laws -- shows the form configured in
 * AdMob's Privacy & messaging only there, and records the answer in the
 * IAB TCF and GPP strings the ad SDK reads. Everywhere else there is no form
 * and ads may be requested at once.
 *
 * The order is Google's: ask for the consent status on every launch, show
 * the form if it is required, and request ads only when [canRequestAds] says
 * so. A person can change their mind at any time from Privacy choices in
 * Profile, which appears whenever the law gives them that right.
 */
object Ads {
    /** Google's test native unit, used in debug builds and whenever no unit is set. */
    private const val TEST_NATIVE_UNIT = "ca-app-pub-3940256099942544/2247696110"

    private val started = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)

    /** True once consent allows ads and the SDK is initialised. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _privacyChoices = MutableStateFlow(false)

    /** Whether Profile must offer Privacy choices: the law here gives the right to revisit consent. */
    val privacyChoices: StateFlow<Boolean> = _privacyChoices.asStateFlow()

    /** The native unit to load, or null when this build serves no ads. */
    val nativeUnit: String?
        get() = when {
            BuildConfig.ADMOB_NATIVE_UNIT.isNotBlank() && !BuildConfig.DEBUG -> BuildConfig.ADMOB_NATIVE_UNIT
            BuildConfig.DEBUG -> TEST_NATIVE_UNIT
            else -> null
        }

    /**
     * Checks consent and, if the law needs it, shows Google's form. Call from
     * the activity on every launch: consent can expire, and the rules change.
     */
    fun gatherConsent(activity: Activity) {
        if (nativeUnit == null) return
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // An error here leaves consent as it was: ads follow
                    // whatever canRequestAds already says, never more.
                    settle(activity, consent)
                }
            },
            { settle(activity, consent) },
        )
        // An answer from an earlier launch still holds while this one is asked.
        if (consent.canRequestAds()) start(activity)
    }

    /** Opens Google's form again, so a person can change what they agreed to. */
    fun showPrivacyChoices(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            settle(activity, UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    private fun settle(activity: Activity, consent: ConsentInformation) {
        _privacyChoices.value = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consent.canRequestAds()) start(activity) else _ready.value = false
    }

    private fun start(activity: Activity) {
        if (!started.compareAndSet(false, true)) {
            _ready.value = true
            return
        }
        val context = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(context) { _ready.value = true }
        }
    }
}
