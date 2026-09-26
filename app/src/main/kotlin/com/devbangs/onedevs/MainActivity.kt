package com.devbangs.onedevs

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.devbangs.onedevs.settings.AppLocale
import com.devbangs.onedevs.ui.OneDevsApp
import com.devbangs.onedevs.ui.theme.OneDevsTheme

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
        installSplashScreen()
        // Transparent scrims on both bars: the default adds a translucent band
        // behind 3-button navigation, which breaks the edge-to-edge surface.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            OneDevsTheme {
                OneDevsApp()
            }
        }
    }
}
