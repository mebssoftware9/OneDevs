package com.devbangs.onedevs.settings

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The languages OneDevs ships. The tags have to match the folder suffixes in
 * res and the entries in xml/locales_config, and a test asserts they do --
 * three places that disagree silently is a picker offering a language the app
 * does not have.
 *
 * [tag] is empty for the system default, which is not a language but the
 * absence of a choice -- and is the default for exactly that reason. Android
 * already picks the closest match between the device's languages and the ones
 * shipped here, so a phone set to French opens OneDevs in French without
 * anyone choosing anything. The picker is for the case where the two should
 * differ.
 */
enum class AppLanguage(val tag: String, val label: String) {
    System("", "System default"),
    English("en", "English"),
    Spanish("es", "Español"),
    French("fr", "Français"),
    Portuguese("pt-BR", "Português (Brasil)"),
}

/**
 * Reading and writing the app's language.
 *
 * Android 13 added per-app languages and a picker in system settings, and
 * LocaleManager is the only correct way to touch them there: writing our own
 * preference as well would give the app two answers, and the one in Settings
 * would be the one the user remembers setting.
 *
 * Below 13 there is no such thing, so this keeps the tag itself and applies it
 * by wrapping the activity's base context. That is the whole backport --
 * AppCompat's version does the same thing behind AppCompatDelegate, but only
 * works if the activity is an AppCompatActivity with an AppCompat theme, which
 * would mean converting a Compose-only app's base class and theme system to
 * get one feature.
 */
object AppLocale {

    private const val PREFS = "locale"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales?.takeUnless { it.isEmpty }?.get(0)?.language.orEmpty()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }
        return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.System
    }

    /**
     * On 13 and above the platform applies this and recreates the activity
     * itself. Below, the caller has to recreate: the locale is only read when
     * a context is built, so nothing already on screen changes until it is.
     */
    fun set(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language.tag.isEmpty()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(language.tag)
                }
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY, language.tag) }
        }
    }

    /**
     * Wraps a base context in the chosen language, for Android 12 and below.
     * A no-op on 13 and above, where the platform has already done it before
     * the activity sees the context.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = base.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return ContextWrapper(base.createConfigurationContext(configuration))
    }
}
