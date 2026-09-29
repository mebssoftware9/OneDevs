package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * The testing and readiness questions an APK can answer about itself, for the
 * tools that were marked "soon" until the analyzer could read layouts,
 * library versions and code markers.
 *
 * Each is honest about its reach. A layout scan cannot see Compose; a DEX
 * scan cannot see what R8 renamed beyond recognition. Where a question has no
 * answer in the file, the check says so instead of passing.
 */
object Quality {

    private const val P = "android.permission."

    // ActivityInfo.CONFIG_* bits, as android:configChanges stores them.
    private const val CONFIG_ORIENTATION = 0x0080
    private const val CONFIG_SCREEN_SIZE = 0x0400
    private const val CONFIG_UI_MODE = 0x0200
    private const val CONFIG_FONT_SCALE = 0x40000000
    private const val CONFIG_DENSITY = 0x1000

    private fun activities(r: ApkReport) = r.componentList.filter { it.kind == ComponentKind.Activity }

    private fun handling(r: ApkReport, bits: Int) =
        activities(r).filter { it.configChanges and bits == bits }.map { Msg.Raw(it.name.substringAfterLast('.')) }

    // ---- Dependencies ------------------------------------------------------

    /**
     * AndroidX splits one library into many groups -- androidx.compose.ui,
     * androidx.compose.material3 -- so it is grouped by its second segment.
     * Everything else is grouped as published.
     */
    internal fun family(group: String): String =
        if (group.startsWith("androidx.")) group.split('.').take(2).joinToString(".") else group

    /** Libraries grouped by where they come from, for the Dependencies Inspector. */
    fun dependencyGroups(r: ApkReport): List<Pair<String, List<Pair<String, String>>>> =
        r.libraries.entries.groupBy { family(it.key.substringBefore(':')) }
            .toSortedMap()
            .map { (group, entries) -> group to entries.map { it.key.substringAfter(':') to it.value } }

    fun dependencies(r: ApkReport): List<Check> = buildList {
        if (r.libraries.isEmpty()) {
            add(Check(Status.Info, str(R.string.q_deps_none), str(R.string.q_deps_none_d)))
            return@buildList
        }
        add(Check(Status.Info, str(R.string.q_deps_count, num(r.libraries.size))))
        // Pre-release artifacts in a release build are a support question
        // waiting to happen: alpha APIs change under an app without notice.
        val unstable = r.libraries.filterValues { v ->
            listOf("alpha", "beta", "-rc", "snapshot", "dev").any { v.lowercase().contains(it) }
        }
        if (unstable.isNotEmpty()) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.q_deps_unstable),
                    str(R.string.q_deps_unstable_d),
                    unstable.map { (k, v) -> Msg.Raw("${k.substringAfter(':')} $v") },
                ),
            )
        } else {
            add(Check(Status.Pass, str(R.string.q_deps_stable)))
        }
        val sdks = r.code.sdks.mapNotNull { KnownSdks.byId(it)?.name }
        if (sdks.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.q_deps_sdks), evidence = sdks.sorted().map { Msg.Raw(it) }))
        }
    }

    // ---- Play Integrity ----------------------------------------------------

    fun integrity(r: ApkReport): List<Check> = buildList {
        val markers = r.codeMarkers
        add(
            if (CodeMarkers.INTEGRITY in markers) {
                Check(Status.Pass, str(R.string.q_integrity_found), str(R.string.q_integrity_found_d))
            } else {
                Check(Status.Info, str(R.string.q_integrity_missing), str(R.string.q_integrity_missing_d))
            },
        )
        if (CodeMarkers.SAFETYNET in markers) {
            add(Check(Status.Fail, str(R.string.q_safetynet), str(R.string.q_safetynet_d)))
        }
        if (!r.requests(P + "INTERNET")) {
            add(Check(Status.Warn, str(R.string.q_no_internet), str(R.string.q_no_internet_d)))
        }
        if (r.debuggable) add(Check(Status.Warn, str(R.string.ck_debuggable), str(R.string.q_integrity_debug_d)))
        if (r.code.obfuscatedPercent?.let { it < 20 } == true || r.compiler?.shrunk == false) {
            add(Check(Status.Info, str(R.string.q_integrity_readable), str(R.string.q_integrity_readable_d)))
        }
        if (CodeMarkers.UPDATE in markers) add(Check(Status.Pass, str(R.string.q_in_app_updates)))
        if (CodeMarkers.REVIEW in markers) add(Check(Status.Pass, str(R.string.q_in_app_review)))
    }

    // ---- Dark mode -----------------------------------------------------------

    fun darkMode(r: ApkReport): List<Check> = buildList {
        when {
            r.nightResources > 0 ->
                add(Check(Status.Pass, str(R.string.q_dark_resources, num(r.nightResources))))
            Checks.resourcesShortened(r) ->
                add(Check(Status.Info, str(R.string.q_dark_unknown), str(R.string.q_dark_unknown_d)))
            else ->
                add(Check(Status.Warn, str(R.string.q_dark_none), str(R.string.q_dark_none_d)))
        }
        val handles = handling(r, CONFIG_UI_MODE)
        if (handles.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.q_dark_handles), str(R.string.q_dark_handles_d), handles))
        }
        add(Check(Status.Info, str(R.string.q_dark_test)))
    }

    // ---- Accessibility -------------------------------------------------------

    fun accessibility(r: ApkReport): List<Check> = buildList {
        val layouts = r.layouts
        if (layouts == null) {
            add(Check(Status.Info, str(R.string.q_layouts_none), str(R.string.q_layouts_none_d)))
            return@buildList
        }
        add(Check(Status.Info, str(R.string.q_layouts_read, num(layouts.files))))
        add(
            if (layouts.unlabelledImages == 0) {
                Check(Status.Pass, str(R.string.q_a11y_images_ok, num(layouts.images)))
            } else {
                Check(
                    Status.Warn,
                    str(R.string.q_a11y_images_bad, num(layouts.unlabelledImages), num(layouts.images)),
                    str(R.string.q_a11y_images_bad_d),
                    layouts.examples.map { Msg.Raw(it) },
                )
            },
        )
        add(
            if (layouts.smallTargets == 0) {
                Check(Status.Pass, str(R.string.q_a11y_targets_ok))
            } else {
                Check(Status.Warn, str(R.string.q_a11y_targets_bad, num(layouts.smallTargets)), str(R.string.q_a11y_targets_bad_d))
            },
        )
        add(Check(Status.Info, str(R.string.q_a11y_talkback)))
    }

    // ---- Font and display scaling -------------------------------------------

    fun fontScaling(r: ApkReport): List<Check> = buildList {
        val layouts = r.layouts
        if (layouts == null) {
            add(Check(Status.Info, str(R.string.q_layouts_none), str(R.string.q_layouts_none_d)))
        } else {
            add(
                if (layouts.textSizesFixed == 0) {
                    Check(Status.Pass, str(R.string.q_font_sp_ok, num(layouts.textSizesSp)))
                } else {
                    Check(
                        Status.Warn,
                        str(R.string.q_font_fixed, num(layouts.textSizesFixed)),
                        str(R.string.q_font_fixed_d),
                        layouts.examples.map { Msg.Raw(it) },
                    )
                },
            )
        }
        val handles = handling(r, CONFIG_FONT_SCALE)
        if (handles.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.q_font_handles), str(R.string.q_font_handles_d), handles))
        }
        val density = handling(r, CONFIG_DENSITY)
        if (density.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.q_density_handles), evidence = density))
        }
        add(Check(Status.Info, str(R.string.q_font_test)))
    }

    // ---- Orientation ---------------------------------------------------------

    fun orientation(r: ApkReport): List<Check> = buildList {
        val locked = activities(r).filter { it.orientationLocked }.map { Msg.Raw(it.name.substringAfterLast('.')) }
        add(
            when {
                locked.isEmpty() -> Check(Status.Pass, str(R.string.ck_orient_free))
                r.targetSdk >= 36 -> Check(Status.Info, str(R.string.ck_orient_ignored), str(R.string.ck_orient_ignored_d), locked)
                else -> Check(Status.Warn, str(R.string.ck_orient_locked), str(R.string.ck_orient_locked_d), locked)
            },
        )
        val handles = handling(r, CONFIG_ORIENTATION or CONFIG_SCREEN_SIZE)
        add(
            if (handles.isEmpty()) {
                Check(Status.Info, str(R.string.q_rotate_recreates), str(R.string.q_rotate_recreates_d))
            } else {
                Check(Status.Info, str(R.string.q_rotate_handles), str(R.string.q_rotate_handles_d), handles)
            },
        )
    }

    // ---- Background and foreground -----------------------------------------

    fun background(r: ApkReport): List<Check> = buildList {
        val typed = r.manifest.serviceTypes.filterValues { it != 0 }
        if (typed.isNotEmpty()) {
            add(
                Check(
                    Status.Info,
                    str(R.string.q_bg_fgs),
                    str(R.string.q_bg_fgs_d),
                    typed.values.flatMap { ForegroundTypes.names(it) }.distinct().map { Msg.Raw(it) },
                ),
            )
        }
        if (r.requests(P + "RECEIVE_BOOT_COMPLETED")) add(Check(Status.Info, str(R.string.q_bg_boot), str(R.string.q_bg_boot_d)))
        if (r.requests(P + "SCHEDULE_EXACT_ALARM") || r.requests(P + "USE_EXACT_ALARM")) {
            add(Check(Status.Info, str(R.string.q_bg_alarms), str(R.string.q_bg_alarms_d)))
        }
        if (r.requests(P + "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
            add(Check(Status.Warn, str(R.string.q_bg_battery), str(R.string.q_bg_battery_d)))
        }
        if (r.requests(P + "ACCESS_BACKGROUND_LOCATION")) {
            add(Check(Status.Warn, str(R.string.q_bg_location), str(R.string.pol_bg_location)))
        }
        if (r.requests(P + "WAKE_LOCK")) add(Check(Status.Info, str(R.string.q_bg_wakelock)))
        if (isEmpty()) add(Check(Status.Pass, str(R.string.q_bg_quiet)))
        add(Check(Status.Info, str(R.string.q_bg_test)))
    }

    // ---- Data Safety ---------------------------------------------------------

    /** A Data Safety form section and why the APK suggests it. */
    data class DataType(val label: Int, val because: List<Msg>)

    private val PERMISSION_TYPES: List<Pair<Int, List<String>>> = listOf(
        R.string.ds_location to listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"),
        R.string.ds_contacts to listOf("READ_CONTACTS", "GET_ACCOUNTS"),
        R.string.ds_photos to listOf("CAMERA", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE"),
        R.string.ds_audio to listOf("RECORD_AUDIO", "READ_MEDIA_AUDIO"),
        R.string.ds_calendar to listOf("READ_CALENDAR"),
        R.string.ds_messages to listOf("READ_SMS", "RECEIVE_SMS", "RECEIVE_MMS"),
        R.string.ds_phone to listOf("READ_CALL_LOG", "READ_PHONE_NUMBERS", "READ_PHONE_STATE"),
        R.string.ds_health to listOf("BODY_SENSORS", "ACTIVITY_RECOGNITION"),
    )

    /**
     * The Data Safety sections this APK's permissions and SDKs point at.
     * A starting list for the form, not the answer to it: what matters is
     * whether the data leaves the device, which no file can say.
     */
    fun dataSafety(r: ApkReport): List<DataType> = buildList {
        PERMISSION_TYPES.forEach { (label, names) ->
            val found = names.filter { r.requests(P + it) }
            if (found.isNotEmpty()) add(DataType(label, found.map { Msg.Raw(it) }))
        }
        if (r.permissions.any { it.startsWith(P + "health.") }) {
            add(DataType(R.string.ds_health, listOf(Msg.Raw("Health Connect"))))
        }
        val sdks = r.code.sdks.mapNotNull { KnownSdks.byId(it) }
        val ids = sdks.filter { it.kind == SdkKind.Ads || it.kind == SdkKind.Attribution || it.kind == SdkKind.Messaging }
            .map { Msg.Raw(it.name) } +
            listOfNotNull(Msg.Raw("AD_ID").takeIf { r.requests("com.google.android.gms.permission.AD_ID") })
        if (ids.isNotEmpty()) add(DataType(R.string.ds_ids, ids))
        val activity = sdks.filter { it.kind == SdkKind.Analytics || it.kind == SdkKind.Attribution }.map { Msg.Raw(it.name) }
        if (activity.isNotEmpty()) add(DataType(R.string.ds_activity, activity))
        val performance = sdks.filter { it.kind == SdkKind.Crashes || it.kind == SdkKind.Analytics }.map { Msg.Raw(it.name) }
        if (performance.isNotEmpty()) add(DataType(R.string.ds_performance, performance))
    }.distinctBy { it.label }

    fun dataSafetyChecks(r: ApkReport): List<Check> = buildList {
        if (!r.requests(P + "INTERNET")) {
            add(Check(Status.Pass, str(R.string.ds_offline), str(R.string.ds_offline_d)))
        } else if (dataSafety(r).isEmpty()) {
            add(Check(Status.Info, str(R.string.ds_nothing), str(R.string.ds_nothing_d)))
        } else {
            add(Check(Status.Info, str(R.string.ds_review), str(R.string.ds_review_d)))
        }
        add(Check(Status.Info, str(R.string.ds_encryption), str(R.string.ds_encryption_d)))
    }
}
