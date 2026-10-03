package com.devbangs.onedevs.ads

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * Consent first, then the launch ad, before the app opens.
 *
 * Google's User Messaging Platform is the consent manager: it knows where
 * the person is and which law applies -- GDPR in the EEA, the UK and
 * Switzerland, the US state privacy laws -- shows the form configured in
 * AdMob's Privacy & messaging only there, and records the answer in the
 * IAB TCF and GPP strings the ad SDK reads. Everywhere else there is no form.
 *
 * The launch ad is the app's revenue, so the app waits for it. While [holding]
 * is true the splash stays on screen (see MainActivity); it is released only
 * when the ad has been shown and closed, or when there is no ad to show:
 *  - the account has a paid plan, which removes ads;
 *  - consent was not given where the law requires it, so no ad may be asked for;
 *  - Google has no ad for this request (no fill, no network). One retry is
 *    made first.
 * [MAX_HOLD_MS] is only a guard against a request that never answers, so
 * nobody is left looking at a splash forever; an ad that arrives after it is
 * dropped rather than thrown over someone already using the app.
 *
 * Privacy choices in Profile reopens the consent form wherever the law gives
 * the right to change an answer.
 */
object Ads {
    /** The longest the app waits on Google before opening without an ad. */
    private const val MAX_HOLD_MS = 15_000L

    private val started = AtomicBoolean(false)
    @Volatile private var initialised = false
    @Volatile private var retried = false

    private val _holding = MutableStateFlow(false)

    /** True while the app is waiting for the launch ad; the splash stays up. */
    val holding: StateFlow<Boolean> = _holding.asStateFlow()

    private val _privacyChoices = MutableStateFlow(false)

    /** Whether Profile must offer Privacy choices: the law here gives the right to revisit consent. */
    val privacyChoices: StateFlow<Boolean> = _privacyChoices.asStateFlow()

    /** Called as the activity is created: an opening holds the app for its ad. */
    fun hold(opened: Boolean) {
        if (opened) {
            retried = false
            _holding.value = true
        }
    }

    /** Opens the app without an ad. */
    fun release() {
        _holding.value = false
    }

    /**
     * Checks consent, shows Google's form if the law needs it, then loads and
     * shows the launch ad. [paid] says whether this account's plan removes ads.
     */
    fun onLaunch(activity: Activity, paid: () -> Boolean) {
        if (paid()) release()
        activity.window.decorView.postDelayed({ release() }, MAX_HOLD_MS)
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // An error here leaves consent as it was: ads follow
                    // whatever canRequestAds already says, never more.
                    settle(activity, consent, paid)
                }
            },
            { settle(activity, consent, paid) },
        )
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
        if (consent.canRequestAds() && !paid()) start(activity) else release()
    }

    private fun start(activity: Activity) {
        if (!started.compareAndSet(false, true)) {
            // Already initialised in this process: this opening only loads.
            if (initialised) load(activity) else release()
            return
        }
        val context = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(context) {
                initialised = true
                activity.runOnUiThread { load(activity) }
            }
        }
    }

    private fun load(activity: Activity) {
        if (!_holding.value) return
        AppOpenAd.load(
            activity.applicationContext,
            BuildConfig.ADMOB_APP_OPEN_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) = whenVisible(activity) { show(activity, ad) }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!retried && _holding.value) {
                        retried = true
                        load(activity)
                    } else {
                        release()
                    }
                }
            },
        )
    }

    /** Runs [block] once the activity is in front, or now if it already is. */
    private fun whenVisible(activity: Activity, block: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return block()
        val lifecycle = owner.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return block()
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        lifecycle.removeObserver(this)
                        block()
                    }
                    Lifecycle.Event.ON_DESTROY -> lifecycle.removeObserver(this)
                    else -> Unit
                }
            }
        })
    }

    private fun show(activity: Activity, ad: AppOpenAd) {
        // Past the guard, the app has opened: an ad now would interrupt.
        if (!_holding.value) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            // The splash is released as the ad closes, so the app opens
            // straight from the ad.
            override fun onAdDismissedFullScreenContent() = release()

            override fun onAdFailedToShowFullScreenContent(error: AdError) = release()
        }
        ad.show(activity)
    }
}
