package com.devbangs.onedevs.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Light, dark, or whatever the phone says.
 *
 * System is the default because a phone that switches at sunset should take
 * this app with it. The override exists because a developer reviewing their own
 * app's screenshots often needs one mode held still.
 */
enum class AppTheme {
    System,
    Light,
    Dark,
}

/**
 * Where the theme choice lives.
 *
 * Held in a Compose state rather than read per-frame from preferences, so the
 * screen changes the moment it is set. Language cannot work this way -- a
 * locale is only read when a context is built, so it needs the activity
 * restarted -- but a theme is just colours, and restarting the activity to
 * change them would be visible and unnecessary.
 */
object ThemeStore {

    private const val PREFS = "settings"
    private const val KEY = "theme"

    var current by mutableStateOf(AppTheme.System)
        private set

    /** Called once, from Application.onCreate, before anything draws. */
    fun load(context: Context) {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AppTheme.System.name)
        current = AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.System
    }

    fun set(context: Context, theme: AppTheme) {
        current = theme
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, theme.name) }
    }
}
