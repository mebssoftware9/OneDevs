package com.devbangs.onedevs

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.devbangs.onedevs.ads.Ads
import com.devbangs.onedevs.data.plans.Tier
import com.devbangs.onedevs.settings.AppLocale
import com.devbangs.onedevs.ui.OneDevsApp
import com.devbangs.onedevs.ui.theme.OneDevsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    /**
     * Applies the chosen language before anything reads a resource. On
     * Android 13 and above this is a no-op -- the platform has already built
     * the context in the right language -- and below it is the whole backport.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Held until the stored session has been looked for, so the app appears
        // already decided. Without it the splash hands over to a loading state
        // and then to a screen -- three things where there should be one.
        val account = (application as OneDevsApplication).account
        installSplashScreen().setKeepOnScreenCondition { !account.ready.value }
        // Transparent scrims on both bars: the default adds a translucent band
        // behind 3-button navigation, which breaks the edge-to-edge surface.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Android still lays its own dark band behind 3-button navigation
        // unless asked not to, which cut every screen off above the buttons.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        setContent {
            OneDevsTheme {
                OneDevsApp()
            }
        }
        // Every launch: consent can expire and the rules change. Shows
        // Google's form only where the law asks for one, then the launch ad
        // for accounts without a paid plan; see ads/Ads.kt.
        //
        // Not over the splash: only once the app is open. The splash leaves
        // when the session is known; a signed-in account's plan is then
        // waited for, so a paid account never sees an ad because its plan
        // had not loaded yet; and the first screen gets a moment on display
        // before anything covers it.
        val plans = (application as OneDevsApplication).plans
        val opened = savedInstanceState == null
        lifecycleScope.launch {
            account.ready.first { it }
            if (account.session.value != null) {
                withTimeoutOrNull(PLAN_WAIT_MS) { plans.plan.first { it != null } }
            }
            delay(SETTLE_MS)
            Ads.onLaunch(this@MainActivity, opened) {
                val tier = plans.plan.value?.tier
                // Signed in with no answer about the plan: no ad, rather than
                // risk showing one to someone who paid to be rid of them.
                account.session.value != null && tier != Tier.Community
            }
        }
    }

    private companion object {
        /** Longest wait for a signed-in account's plan before deciding about an ad. */
        const val PLAN_WAIT_MS = 4_000L

        /** How long the first screen is on display before the launch ad may cover it. */
        const val SETTLE_MS = 700L
    }
}
