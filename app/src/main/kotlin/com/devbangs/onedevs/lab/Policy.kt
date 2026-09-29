package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * A third-party SDK recognisable from its package names.
 *
 * Found means the classes are in the DEX. Not found means nothing: R8 can
 * repackage a library until its name is gone, so this list only ever adds to
 * what a developer already knows.
 */
data class KnownSdk(val id: String, val name: String, val kind: SdkKind, val prefixes: List<String>)

enum class SdkKind(val label: Int) {
    Analytics(R.string.sdk_analytics),
    Ads(R.string.sdk_ads),
    Attribution(R.string.sdk_attribution),
    Crashes(R.string.sdk_crashes),
    Messaging(R.string.sdk_messaging),
}

/**
 * SDKs whose data collection belongs in the Data Safety form.
 *
 * Deliberately short and deliberately these: each one sends something off the
 * device on its own, which is what the form asks about. A UI library is not
 * on it however popular, because it changes nothing a developer declares.
 */
internal object KnownSdks {
    val all = listOf(
        KnownSdk("firebase-analytics", "Google Analytics for Firebase", SdkKind.Analytics,
            listOf("com/google/firebase/analytics/", "com/google/android/gms/measurement/")),
        KnownSdk("crashlytics", "Firebase Crashlytics", SdkKind.Crashes,
            listOf("com/google/firebase/crashlytics/", "com/crashlytics/")),
        KnownSdk("fcm", "Firebase Cloud Messaging", SdkKind.Messaging, listOf("com/google/firebase/messaging/")),
        KnownSdk("gma", "Google Mobile Ads", SdkKind.Ads, listOf("com/google/android/gms/ads/")),
        KnownSdk("meta-events", "Meta App Events", SdkKind.Analytics, listOf("com/facebook/appevents/")),
        KnownSdk("meta-ads", "Meta Audience Network", SdkKind.Ads, listOf("com/facebook/ads/")),
        KnownSdk("appsflyer", "AppsFlyer", SdkKind.Attribution, listOf("com/appsflyer/")),
        KnownSdk("adjust", "Adjust", SdkKind.Attribution, listOf("com/adjust/sdk/")),
        KnownSdk("branch", "Branch", SdkKind.Attribution, listOf("io/branch/")),
        KnownSdk("applovin", "AppLovin", SdkKind.Ads, listOf("com/applovin/")),
        KnownSdk("unity-ads", "Unity Ads", SdkKind.Ads, listOf("com/unity3d/ads/", "com/unity3d/services/")),
        KnownSdk("ironsource", "ironSource", SdkKind.Ads, listOf("com/ironsource/")),
        KnownSdk("mixpanel", "Mixpanel", SdkKind.Analytics, listOf("com/mixpanel/")),
        KnownSdk("amplitude", "Amplitude", SdkKind.Analytics, listOf("com/amplitude/")),
        KnownSdk("segment", "Segment", SdkKind.Analytics, listOf("com/segment/analytics/")),
        KnownSdk("appmetrica", "AppMetrica", SdkKind.Analytics, listOf("io/appmetrica/", "com/yandex/metrica/")),
        KnownSdk("clarity", "Microsoft Clarity", SdkKind.Analytics, listOf("com/microsoft/clarity/")),
        KnownSdk("onesignal", "OneSignal", SdkKind.Messaging, listOf("com/onesignal/")),
        KnownSdk("sentry", "Sentry", SdkKind.Crashes, listOf("io/sentry/")),
        KnownSdk("bugsnag", "Bugsnag", SdkKind.Crashes, listOf("com/bugsnag/")),
    )

    fun byId(id: String): KnownSdk? = all.firstOrNull { it.id == id }
}

/**
 * Permissions Play will not accept without a declaration in Play Console.
 *
 * Google's list, kept here because it changes with policy and the one place it
 * is written should be the place someone thinks to check -- as with the
 * target SDK floor. Each maps to the sentence that says what Play wants.
 */
internal object PlayPolicy {

    private const val P = "android.permission."

    private val SMS_AND_CALLS = listOf(
        "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS",
    ).map { P + it }

    val declarations: Map<String, Int> = buildMap {
        SMS_AND_CALLS.forEach { put(it, R.string.pol_sms) }
        put(P + "MANAGE_EXTERNAL_STORAGE", R.string.pol_all_files)
        put(P + "QUERY_ALL_PACKAGES", R.string.pol_query_all)
        put(P + "REQUEST_INSTALL_PACKAGES", R.string.pol_install)
        put(P + "ACCESS_BACKGROUND_LOCATION", R.string.pol_bg_location)
        put(P + "READ_MEDIA_IMAGES", R.string.pol_media)
        put(P + "READ_MEDIA_VIDEO", R.string.pol_media)
        put(P + "USE_EXACT_ALARM", R.string.pol_exact_alarm)
        put(P + "USE_FULL_SCREEN_INTENT", R.string.pol_full_screen)
        put("com.google.android.gms.permission.AD_ID", R.string.pol_ad_id)
    }

    /** The policy sentence for a permission, if Play wants a declaration for it. */
    fun declarationFor(permission: String, targetSdk: Int): Int? = when {
        permission in declarations -> declarations[permission]
        permission.startsWith(P + "FOREGROUND_SERVICE_") && targetSdk >= 34 -> R.string.pol_fgs
        permission.startsWith(P + "health.") -> R.string.pol_health
        else -> null
    }

    /** Services bound with these permissions need a declaration of their own. */
    val servicePermissions: Map<String, Int> = mapOf(
        P + "BIND_ACCESSIBILITY_SERVICE" to R.string.pol_accessibility,
        P + "BIND_VPN_SERVICE" to R.string.pol_vpn,
    )
}

/**
 * Foreground service types and the permission each needs from Android 14.
 *
 * The bit values are ServiceInfo's FOREGROUND_SERVICE_TYPE_* constants, which
 * are also what aapt2 writes into the manifest. shortService needs none.
 */
internal object ForegroundTypes {
    val permissions: Map<Int, Pair<String, String>> = mapOf(
        0x1 to ("dataSync" to "DATA_SYNC"),
        0x2 to ("mediaPlayback" to "MEDIA_PLAYBACK"),
        0x4 to ("phoneCall" to "PHONE_CALL"),
        0x8 to ("location" to "LOCATION"),
        0x10 to ("connectedDevice" to "CONNECTED_DEVICE"),
        0x20 to ("mediaProjection" to "MEDIA_PROJECTION"),
        0x40 to ("camera" to "CAMERA"),
        0x80 to ("microphone" to "MICROPHONE"),
        0x100 to ("health" to "HEALTH"),
        0x200 to ("remoteMessaging" to "REMOTE_MESSAGING"),
        0x400 to ("systemExempted" to "SYSTEM_EXEMPTED"),
        0x2000 to ("mediaProcessing" to "MEDIA_PROCESSING"),
        0x40000000 to ("specialUse" to "SPECIAL_USE"),
    )

    /** The names of the types set in [flags], shortService included. */
    fun names(flags: Int): List<String> = buildList {
        permissions.forEach { (bit, pair) -> if (flags and bit != 0) add(pair.first) }
        if (flags and 0x800 != 0) add("shortService")
    }

    /** Permissions [flags] needs, as full names. */
    fun required(flags: Int): List<String> =
        permissions.filterKeys { flags and it != 0 }.values.map { "android.permission.FOREGROUND_SERVICE_" + it.second }
}

/**
 * Libraries recognisable from their classes that decide something about
 * release: integrity checks, in-app updates, reviews, billing. Unlike
 * [KnownSdks] these send nothing of their own, so they are not a Data Safety
 * question -- they are a readiness one.
 */
internal object CodeMarkers {
    const val INTEGRITY = "integrity"
    const val SAFETYNET = "safetynet"
    const val BILLING = "billing"
    const val REVIEW = "review"
    const val UPDATE = "update"

    val all: Map<String, List<String>> = mapOf(
        INTEGRITY to listOf("com/google/android/play/core/integrity/", "com/google/android/play/integrity/"),
        SAFETYNET to listOf("com/google/android/gms/safetynet/"),
        BILLING to listOf("com/android/billingclient/"),
        REVIEW to listOf("com/google/android/play/core/review/"),
        UPDATE to listOf("com/google/android/play/core/appupdate/"),
    )
}
