package com.devbangs.onedevs.ads

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.devbangs.onedevs.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
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
 * Consent first, then one app open ad when the app is opened.
 *
 * Google's User Messaging Platform is the consent manager: it knows where
 * the person is and which law applies -- GDPR in the EEA, the UK and
 * Switzerland, the US state privacy laws -- shows the form configured in
 * AdMob's Privacy & messaging only there, and records the answer in the
 * IAB TCF and GPP strings the ad SDK reads. Everywhere else there is no form.
 *
 * The order is Google's: ask for the consent status on every launch, show
 * the form if it is required, and request an ad only when [canRequestAds]
 * says so. Privacy choices in Profile reopens the form wherever the law
 * gives the right to change an answer.
 *
 * The ad is shown once per launch of the app, after its first screen is on
 * display (MainActivity calls [onLaunch] only then, never over the splash),
 * and only:
 *  - when it is ready within [WINDOW_MS] of that -- an ad that arrives
 *    after the person has started using the app would interrupt them, so it
 *    is dropped instead;
 *  - when the consent form was not just on screen -- two full-screen things
 *    in a row on someone's first launch is one too many;
 *  - for an account without a paid plan.
 * Coming back from another app shows nothing.
 */
object Ads {
    /** How long after opening an ad may still appear. */
    private const val WINDOW_MS = 5_000L

    private val started = AtomicBoolean(false)
    @Volatile private var initialised = false
    private val shown = AtomicBoolean(false)
    @Volatile private var launchedAt = SystemClock.elapsedRealtime()
    @Volatile private var formThisLaunch = false

    private val _privacyChoices = MutableStateFlow(false)

    /** Whether Profile must offer Privacy choices: the law here gives the right to revisit consent. */
    val privacyChoices: StateFlow<Boolean> = _privacyChoices.asStateFlow()

    /**
     * Checks consent, shows Google's form if the law needs it, then loads and
     * shows the launch ad. [paid] answers whether this account has a plan
     * that removes ads. [opened] is false when the activity is only being
     * recreated -- a rotation, a language change -- which is not an opening.
     */
    fun onLaunch(activity: Activity, opened: Boolean, paid: () -> Boolean) {
        if (opened) {
            // A new opening, even in a process Android kept alive: the window
            // and the once-per-opening rule start again.
            launchedAt = SystemClock.elapsedRealtime()
            shown.set(false)
            formThisLaunch = false
        }
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (consent.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) formThisLaunch = true
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // An error here leaves consent as it was: ads follow
                    // whatever canRequestAds already says, never more.
                    settle(activity, consent, paid)
                }
            },
            { settle(activity, consent, paid) },
        )
        // An answer from an earlier launch still holds while this one is asked.
        if (consent.canRequestAds()) start(activity, paid)
    }

    /** Opens Google's form again, so a person can change what they agreed to. */
    fun showPrivacyChoices(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            _privacyChoices.value = UserMessagingPlatform.getConsentInformation(activity)
                .privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        }
    }

    private fun settle(activity: Activity, consent: ConsentInformation, paid: () -> Boolean) {
        _privacyChoices.value = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consent.canRequestAds()) start(activity, paid)
    }

    private fun start(activity: Activity, paid: () -> Boolean) {
        if (!started.compareAndSet(false, true)) {
            // Already initialised in this process: this opening only loads.
            if (initialised) load(activity, paid)
            return
        }
        val context = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(context) {
                initialised = true
                activity.runOnUiThread { load(activity, paid) }
            }
        }
    }

    private fun load(activity: Activity, paid: () -> Boolean) {
        if (formThisLaunch || paid() || late() || shown.get()) return
        AppOpenAd.load(
            activity.applicationContext,
            BuildConfig.ADMOB_APP_OPEN_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) = show(activity, ad, paid)

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No fill is ordinary: the app simply opens.
                }
            },
        )
    }

    private fun show(activity: Activity, ad: AppOpenAd, paid: () -> Boolean) {
        val visible = (activity as? LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (!visible || formThisLaunch || paid() || late() || !shown.compareAndSet(false, true)) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdFailedToShowFullScreenContent(error: AdError) = Unit
        }
        ad.show(activity)
    }

    private fun late(): Boolean = SystemClock.elapsedRealtime() - launchedAt > WINDOW_MS
}
