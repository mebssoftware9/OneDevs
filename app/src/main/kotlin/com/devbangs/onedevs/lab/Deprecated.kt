package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * Android APIs that are deprecated, removed, or no longer accepted by Google
 * Play, found by what the DEX refers to.
 *
 * A DEX lists every class it mentions in type_ids and every method it calls in
 * method_ids, the platform's included. So a call to
 * Environment.getExternalStorageDirectory() leaves that exact pair in the file
 * whether or not the app was shrunk: R8 renames the app's own classes, never
 * Android's.
 *
 * What the file cannot say is who made the call. A library may reference an
 * old API behind a version check, which is harmless, and the tool says so
 * rather than pretending every hit is the developer's.
 */
internal object DeprecatedApis {

    enum class Kind {
        /** Deprecated in [Api.since]: still works, on borrowed time. */
        Deprecated,

        /** Gone from the platform in [Api.since]: crashes or does nothing there. */
        Removed,

        /** Still in Android, no longer accepted on Google Play. */
        Play,

        /**
         * Not a problem at all: a capability other tools ask about -- a
         * WebView, a sign-in library, a shared database. Found by the same
         * scan because it costs nothing extra.
         */
        Signal,
    }

    /**
     * One API. [owner] is a type descriptor, or a package prefix ending in '/'
     * when [prefix] is set. [member] narrows it to a method; [shorty] narrows
     * that to one overload, in DEX shorty form ("VJ" is void(long)).
     */
    data class Api(
        val id: String,
        val owner: String,
        val since: Int,
        val use: String,
        val kind: Kind = Kind.Deprecated,
        val member: String? = null,
        val shorty: String? = null,
        val prefix: Boolean = false,
    ) {
        /** android.os.Environment.getExternalStorageDirectory, the way a developer searches for it. */
        val label: String
            get() {
                val type = owner.removePrefix("L").removeSuffix(";").removeSuffix("/").replace('/', '.').replace('$', '.')
                return when {
                    prefix -> "$type.*"
                    member == "<init>" -> "new ${type.substringAfterLast('.')}(" + (shorty?.drop(1)?.let { args(it) } ?: "") + ")"
                    member != null -> "$type.$member"
                    else -> type
                }
            }

        private fun args(shorty: String) = shorty.map {
            when (it) {
                'J' -> "long"
                'I' -> "int"
                'Z' -> "boolean"
                'L' -> "Object"
                else -> it.toString()
            }
        }.joinToString(", ")
    }

    val all = listOf(
        Api("asynctask", "Landroid/os/AsyncTask;", 30, "Kotlin coroutines or java.util.concurrent"),
        Api("intentservice", "Landroid/app/IntentService;", 30, "WorkManager"),
        Api("preferencemanager", "Landroid/preference/PreferenceManager;", 29, "androidx.preference"),
        Api("progressdialog", "Landroid/app/ProgressDialog;", 26, "LinearProgressIndicator or CircularProgressIndicator"),
        Api("networkinfo", "Landroid/net/NetworkInfo;", 29, "ConnectivityManager.NetworkCallback and NetworkCapabilities"),
        Api("renderscript", "Landroid/renderscript/RenderScript;", 31, "Vulkan or the RenderScript Intrinsics Replacement Toolkit"),
        Api("camera1", "Landroid/hardware/Camera;", 21, "CameraX"),
        Api("platform-fragment", "Landroid/app/Fragment;", 28, "androidx.fragment.app.Fragment"),
        Api("tabactivity", "Landroid/app/TabActivity;", 13, "ViewPager2 with TabLayout"),
        Api("external-dir", "Landroid/os/Environment;", 29, "Context.getExternalFilesDir or MediaStore",
            member = "getExternalStorageDirectory"),
        Api("external-public-dir", "Landroid/os/Environment;", 29, "MediaStore",
            member = "getExternalStoragePublicDirectory"),
        Api("handler-no-looper", "Landroid/os/Handler;", 30, "Handler(Looper.getMainLooper())",
            member = "<init>", shorty = "V"),
        Api("display-size", "Landroid/view/Display;", 30, "WindowManager.getCurrentWindowMetrics()", member = "getSize"),
        Api("display-metrics", "Landroid/view/Display;", 30, "WindowManager.getCurrentWindowMetrics()", member = "getMetrics"),
        Api("default-display", "Landroid/view/WindowManager;", 30, "Context.getDisplay() or WindowMetrics",
            member = "getDefaultDisplay"),
        Api("system-ui-visibility", "Landroid/view/View;", 30, "WindowInsetsControllerCompat",
            member = "setSystemUiVisibility"),
        Api("status-bar-color", "Landroid/view/Window;", 35, "edge-to-edge with enableEdgeToEdge()",
            member = "setStatusBarColor"),
        Api("navigation-bar-color", "Landroid/view/Window;", 35, "edge-to-edge with enableEdgeToEdge()",
            member = "setNavigationBarColor"),
        Api("pending-transition", "Landroid/app/Activity;", 34, "Activity.overrideActivityTransition()",
            member = "overridePendingTransition"),
        Api("vibrate-ms", "Landroid/os/Vibrator;", 26, "Vibrator.vibrate(VibrationEffect)", member = "vibrate", shorty = "VJ"),
        Api("notification-no-channel", "Landroid/app/Notification\$Builder;", 26,
            "Notification.Builder(context, channelId)", member = "<init>", shorty = "VL"),
        Api("html-one-arg", "Landroid/text/Html;", 24, "HtmlCompat.fromHtml(text, flags)", member = "fromHtml", shorty = "LL"),
        Api("device-id", "Landroid/telephony/TelephonyManager;", 26, "UUID.randomUUID(), stored per install",
            member = "getDeviceId"),
        Api("wifi-enabled", "Landroid/net/wifi/WifiManager;", 29, "Settings.Panel.ACTION_WIFI", member = "setWifiEnabled"),
        Api("app-cache", "Landroid/webkit/WebSettings;", 33, "Cache-Control headers", kind = Kind.Removed,
            member = "setAppCacheEnabled"),
        Api("apache-http", "Lorg/apache/http/", 23, "HttpURLConnection or OkHttp", kind = Kind.Removed, prefix = true),
        Api("support-library", "Landroid/support/", 28, "AndroidX", prefix = true),
        Api("aidl-billing", "Lcom/android/vending/billing/", 0, "Play Billing Library", kind = Kind.Play, prefix = true),
        Api("safetynet", "Lcom/google/android/gms/safetynet/", 0, "Play Integrity API", kind = Kind.Play, prefix = true),
        Api(WEBVIEW, "Landroid/webkit/WebView;", 0, "", kind = Kind.Signal),
        Api(SIGN_IN_GOOGLE, "Lcom/google/android/gms/auth/api/signin/", 0, "", kind = Kind.Signal, prefix = true),
        Api(SIGN_IN_CREDENTIALS, "Landroidx/credentials/", 0, "", kind = Kind.Signal, prefix = true),
        Api(SIGN_IN_FIREBASE, "Lcom/google/firebase/auth/", 0, "", kind = Kind.Signal, prefix = true),
        Api(SHARED_FIRESTORE, "Lcom/google/firebase/firestore/", 0, "", kind = Kind.Signal, prefix = true),
        Api(SHARED_DATABASE, "Lcom/google/firebase/database/", 0, "", kind = Kind.Signal, prefix = true),
    )

    const val WEBVIEW = "signal-webview"
    const val SIGN_IN_GOOGLE = "signal-google-sign-in"
    const val SIGN_IN_CREDENTIALS = "signal-credentials"
    const val SIGN_IN_FIREBASE = "signal-firebase-auth"
    const val SHARED_FIRESTORE = "signal-firestore"
    const val SHARED_DATABASE = "signal-realtime-database"

    /** Code that signs people in: Google Sign-In, Credential Manager or Firebase Auth. */
    fun signsIn(r: ApkReport): Boolean =
        listOf(SIGN_IN_GOOGLE, SIGN_IN_CREDENTIALS, SIGN_IN_FIREBASE).any { it in r.code.apis }

    /** A database other users write to as well, which is where shared content lives. */
    fun sharesContent(r: ApkReport): Boolean = SHARED_FIRESTORE in r.code.apis || SHARED_DATABASE in r.code.apis

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Api? = byId[id]

    private val types: Map<String, List<Api>> = all.filter { !it.prefix }.groupBy { it.owner }
    private val prefixes = all.filter { it.prefix }

    /**
     * The [Api.id]s a DEX refers to. Bounds are checked against the array at
     * every step, because the file came from whoever built the APK.
     */
    fun find(dex: ByteArray, string: (Long) -> String?): Set<String> {
        if (dex.size < 0x70) return emptySet()
        val stringIdsSize = u32(dex, 0x38)
        val stringIdsOff = u32(dex, 0x3C)
        val typeIdsSize = u32(dex, 0x40)
        val typeIdsOff = u32(dex, 0x44)
        val protoIdsSize = u32(dex, 0x48)
        val protoIdsOff = u32(dex, 0x4C)
        val methodIdsSize = u32(dex, 0x58)
        val methodIdsOff = u32(dex, 0x5C)
        if (!fits(dex, stringIdsOff, stringIdsSize * 4) || !fits(dex, typeIdsOff, typeIdsSize * 4)) return emptySet()

        fun stringAt(index: Long): String? {
            if (index < 0 || index >= stringIdsSize) return null
            return string(u32(dex, (stringIdsOff + index * 4).toInt()))
        }

        val found = HashSet<String>()
        // Types with method-level entries, by their index, for the second pass.
        val watched = HashMap<Int, List<Api>>()
        for (i in 0 until typeIdsSize.toInt()) {
            val descriptor = stringAt(u32(dex, (typeIdsOff + i * 4L).toInt())) ?: continue
            types[descriptor]?.let { apis ->
                apis.filter { it.member == null }.forEach { found += it.id }
                apis.filter { it.member != null }.takeIf { it.isNotEmpty() }?.let { watched[i] = it }
            }
            prefixes.forEach { if (descriptor.startsWith(it.owner)) found += it.id }
        }
        if (watched.isEmpty() || !fits(dex, methodIdsOff, methodIdsSize * 8)) return found
        val protos = fits(dex, protoIdsOff, protoIdsSize * 12)
        for (i in 0 until methodIdsSize.toInt()) {
            val at = (methodIdsOff + i * 8L).toInt()
            val apis = watched[u16(dex, at)] ?: continue
            val name = stringAt(u32(dex, at + 4)) ?: continue
            apis.forEach { api ->
                if (api.member != name || api.id in found) return@forEach
                if (api.shorty != null) {
                    val proto = u16(dex, at + 2).toLong()
                    if (!protos || proto >= protoIdsSize) return@forEach
                    if (stringAt(u32(dex, (protoIdsOff + proto * 12).toInt())) != api.shorty) return@forEach
                }
                found += api.id
            }
        }
        return found
    }

    private fun fits(b: ByteArray, offset: Long, length: Long): Boolean =
        length == 0L || (offset > 0 && offset + length <= b.size)

    /** Google Play's floor for the Play Billing Library, as a major version. */
    const val BILLING_FLOOR = 8

    /**
     * What the tool says about [r]: one check per API found, the Play Billing
     * Library's version when it declares one, and what the scan cannot see.
     */
    fun checks(r: ApkReport): List<Check> = buildList {
        val hits = r.code.apis.mapNotNull { byId(it) }.filter { it.kind != Kind.Signal }
            .sortedWith(compareBy({ it.kind != Kind.Play }, { it.kind != Kind.Removed }, { -it.since }))
        hits.forEach { api ->
            val status = when {
                api.kind == Kind.Play -> Status.Fail
                api.kind == Kind.Removed && r.targetSdk >= api.since -> Status.Fail
                else -> Status.Warn
            }
            val detail = when (api.kind) {
                Kind.Deprecated -> str(R.string.dep_deprecated, api.since, api.use)
                Kind.Removed -> str(R.string.dep_removed, api.since, api.use)
                Kind.Play, Kind.Signal -> str(R.string.dep_play, api.use)
            }
            add(Check(status, Msg.Raw(api.label), detail))
        }
        r.libraries["com.android.billingclient:billing"]?.let { version ->
            val major = version.substringBefore('.').toIntOrNull()
            if (major != null && major < BILLING_FLOOR) {
                add(Check(Status.Fail, str(R.string.dep_billing_old, version), str(R.string.dep_billing_old_d, BILLING_FLOOR)))
            } else if (major != null) {
                add(Check(Status.Pass, str(R.string.dep_billing_ok, version)))
            }
        }
        if (hits.isEmpty()) {
            add(
                Check(
                    if (r.dexFiles.isEmpty()) Status.Info else Status.Pass,
                    str(if (r.dexFiles.isEmpty()) R.string.dep_no_code else R.string.dep_none),
                ),
            )
        } else {
            add(Check(Status.Info, str(R.string.dep_libraries), str(R.string.dep_libraries_d)))
        }
    }
}
