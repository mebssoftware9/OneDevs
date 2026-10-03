package com.devbangs.onedevs.lab

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * The APK the Lab last read, kept so it can read it again.
 *
 * The analysis itself is too large to keep, and only lives while the Lab is
 * open. What is kept is the way back to the file: its document URI, with
 * Android's lasting permission to read it. Opening a tool, or the Lab after
 * the app was closed, reads the same file again instead of asking for it.
 */
object LabMemory {
    private const val PREFS = "lab_apk"
    private const val LAST = "last"

    /** Remembers [uri] as the file for [packageName], and as the current one. */
    fun remember(context: Context, packageName: String, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // A provider that does not grant lasting access: the file is good
            // for this session, and the next one asks again.
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(packageName, uri.toString())
            putString(LAST, packageName)
        }
    }

    /** The package the Lab worked on last, if any. */
    fun lastPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)

    /** The file kept for [packageName], if Android still lets the app read it. */
    fun fileFor(context: Context, packageName: String): Uri? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(packageName, null)?.toUri() ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return uri.takeIf { held }
    }

    /** Forgets a file that could no longer be read. */
    fun forget(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(packageName, null)?.toUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.edit {
            remove(packageName)
            if (prefs.getString(LAST, null) == packageName) remove(LAST)
        }
    }
}
