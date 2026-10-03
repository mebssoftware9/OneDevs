package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * The listing-side questions an APK can answer: what Play Console's App
 * content page will ask, what the content rating questionnaire needs a "yes"
 * for, whether the listing's words agree with the code, and whether a privacy
 * policy covers what the app does.
 *
 * None of these can fill in a form for the developer, and none pretend to.
 * Each says what the file shows and which answer that implies; the parts the
 * file cannot know -- violence, gambling, who the app is for -- are said to
 * be the developer's call.
 */
object Store {

    private const val P = "android.permission."
    private const val AD_ID = "com.google.android.gms.permission.AD_ID"

    private val LOCATION = listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION").map { P + it }

    private fun sdksOf(r: ApkReport, kind: SdkKind) =
        r.code.sdks.mapNotNull { KnownSdks.byId(it) }.filter { it.kind == kind }

    fun hasAds(r: ApkReport): Boolean = sdksOf(r, SdkKind.Ads).isNotEmpty()

    private fun sells(r: ApkReport): Boolean =
        CodeMarkers.BILLING in r.codeMarkers || r.requests("com.android.vending.BILLING")

    // ---- App content ---------------------------------------------------------

    /** Play Console → Policy → App content, item by item. */
    fun appContent(r: ApkReport): List<Check> = buildList {
        add(Check(Status.Warn, str(R.string.ac_policy), str(R.string.ac_policy_d)))
        val ads = sdksOf(r, SdkKind.Ads)
        add(
            if (ads.isNotEmpty()) {
                Check(Status.Warn, str(R.string.ac_ads_yes), str(R.string.ac_ads_yes_d), ads.map { Msg.Raw(it.name) })
            } else {
                Check(Status.Info, str(R.string.ac_ads_no), str(R.string.ac_ads_no_d))
            },
        )
        if (r.requests(AD_ID) || ads.isNotEmpty()) {
            add(Check(Status.Warn, str(R.string.ac_ad_id), str(R.string.ac_ad_id_d)))
        }
        if (DeprecatedApis.signsIn(r)) {
            add(Check(Status.Warn, str(R.string.ac_access), str(R.string.ac_access_d)))
            add(Check(Status.Warn, str(R.string.ac_deletion), str(R.string.ac_deletion_d)))
        } else {
            add(Check(Status.Info, str(R.string.ac_access_maybe), str(R.string.ac_access_maybe_d)))
        }
        val declarations = r.permissions.mapNotNull { p -> PlayPolicy.declarationFor(p, r.targetSdk)?.let { p to it } }
        declarations.forEach { (permission, sentence) ->
            add(Check(Status.Warn, Msg.Raw(permission.substringAfterLast('.')), str(sentence)))
        }
        if (r.permissions.any { it.startsWith(P + "health.") }) {
            add(Check(Status.Warn, str(R.string.ac_health), str(R.string.ac_health_d)))
        }
        add(Check(Status.Info, str(R.string.ac_data_safety), str(R.string.ac_data_safety_d)))
        add(Check(Status.Info, str(R.string.ac_audience), str(R.string.ac_audience_d)))
        add(Check(Status.Info, str(R.string.ac_rating), str(R.string.ac_rating_d)))
    }

    // ---- Content rating --------------------------------------------------------

    /** The questionnaire's answers the code implies, and the ones only the developer knows. */
    fun contentRating(r: ApkReport): List<Check> = buildList {
        if (sells(r)) add(Check(Status.Warn, str(R.string.cr_purchases), str(R.string.cr_purchases_d)))
        if (DeprecatedApis.sharesContent(r)) add(Check(Status.Warn, str(R.string.cr_interaction), str(R.string.cr_interaction_d)))
        if (DeprecatedApis.WEBVIEW in r.code.apis) add(Check(Status.Info, str(R.string.cr_web), str(R.string.cr_web_d)))
        if (LOCATION.any { r.requests(it) }) add(Check(Status.Info, str(R.string.cr_location), str(R.string.cr_location_d)))
        if (hasAds(r)) add(Check(Status.Info, str(R.string.cr_ads), str(R.string.cr_ads_d)))
        if (isEmpty()) add(Check(Status.Pass, str(R.string.cr_nothing)))
        add(Check(Status.Info, str(R.string.cr_yours), str(R.string.cr_yours_d)))
    }

    // ---- Listing consistency -----------------------------------------------------

    private fun says(text: String, vararg phrases: String): Boolean {
        val lower = text.lowercase()
        return phrases.any { lower.contains(it) }
    }

    /** Whether the listing and the app describe the same thing. */
    fun consistency(r: ApkReport, listing: Listing): List<Check> = buildList {
        val text = listOf(listing.title, listing.short, listing.full).joinToString("\n")
        val label = r.label.trim()
        if (label.isNotEmpty() && listing.title.isNotBlank()) {
            val matches = listing.title.contains(label, ignoreCase = true) || label.contains(listing.title.trim(), ignoreCase = true)
            add(
                if (matches) {
                    Check(Status.Pass, str(R.string.lc_name_ok, label))
                } else {
                    Check(Status.Warn, str(R.string.lc_name_differs, label), str(R.string.lc_name_differs_d))
                },
            )
        }
        if (hasAds(r) && says(text, "no ads", "ad-free", "ad free", "without ads", "zero ads")) {
            add(Check(Status.Fail, str(R.string.lc_ads), str(R.string.lc_ads_d)))
        }
        if (r.requests(P + "INTERNET") && says(text, "works offline", "no internet", "100% offline", "fully offline", "without internet")) {
            add(Check(Status.Warn, str(R.string.lc_offline), str(R.string.lc_offline_d)))
        }
        if (DeprecatedApis.signsIn(r) && says(text, "no account", "no sign-up", "no signup", "no login", "no registration")) {
            add(Check(Status.Warn, str(R.string.lc_account), str(R.string.lc_account_d)))
        }
        if (sells(r) && says(text, "completely free", "100% free", "totally free", "free forever")) {
            add(Check(Status.Warn, str(R.string.lc_free), str(R.string.lc_free_d)))
        }
        if (sdksOf(r, SdkKind.Analytics).isNotEmpty() && says(text, "no tracking", "no analytics", "we don't track", "we do not track")) {
            add(Check(Status.Fail, str(R.string.lc_tracking), str(R.string.lc_tracking_d)))
        }
        val unexplained = PERMISSION_WORDS.filter { (permission, words) ->
            r.requests(P + permission) && words.none { text.contains(it, ignoreCase = true) }
        }.map { Msg.Raw(it.first) }
        if (unexplained.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.lc_permissions), str(R.string.lc_permissions_d), unexplained))
        }
        if (none { it.status != Status.Pass }) add(Check(Status.Pass, str(R.string.lc_agree)))
        add(Check(Status.Info, str(R.string.lc_english), str(R.string.lc_english_d)))
    }

    /** Runtime permissions, and the words a listing would use if it explained them. */
    private val PERMISSION_WORDS = listOf(
        "CAMERA" to listOf("camera", "photo", "scan", "picture", "video"),
        "RECORD_AUDIO" to listOf("microphone", "record", "voice", "audio"),
        "ACCESS_FINE_LOCATION" to listOf("location", "gps", "map", "nearby"),
        "READ_CONTACTS" to listOf("contact"),
        "READ_CALENDAR" to listOf("calendar", "event"),
        "BODY_SENSORS" to listOf("sensor", "heart", "fitness"),
        "BLUETOOTH_CONNECT" to listOf("bluetooth"),
    )

    // ---- Privacy policy ------------------------------------------------------------

    /** What fetching the policy URL returned. [text] is the page with its markup removed. */
    data class Page(val url: String, val status: Int, val contentType: String, val text: String)

    /** The policy against the app: reachable, readable, and covering what the code does. */
    fun privacyPolicy(r: ApkReport?, page: Page): List<Check> = buildList {
        add(
            if (page.url.startsWith("https://", ignoreCase = true)) {
                Check(Status.Pass, str(R.string.pp_https))
            } else {
                Check(Status.Fail, str(R.string.pp_http), str(R.string.pp_http_d))
            },
        )
        if (page.status !in 200..299) {
            add(Check(Status.Fail, str(R.string.pp_status, page.status), str(R.string.pp_status_d)))
            return@buildList
        }
        add(Check(Status.Pass, str(R.string.pp_reachable)))
        if (page.contentType.contains("pdf", ignoreCase = true)) {
            add(Check(Status.Warn, str(R.string.pp_pdf), str(R.string.pp_pdf_d)))
            return@buildList
        }
        val text = page.text
        if (text.length < SHORT_POLICY) {
            add(Check(Status.Warn, str(R.string.pp_short), str(R.string.pp_short_d)))
            return@buildList
        }
        val names = listOfNotNull(r?.label?.takeIf { it.length > 1 }, r?.packageName)
        if (names.isNotEmpty()) {
            add(
                if (names.any { text.contains(it, ignoreCase = true) }) {
                    Check(Status.Pass, str(R.string.pp_names))
                } else {
                    Check(Status.Warn, str(R.string.pp_no_name), str(R.string.pp_no_name_d))
                },
            )
        }
        add(
            if (EMAIL.containsMatchIn(text)) {
                Check(Status.Pass, str(R.string.pp_contact))
            } else {
                Check(Status.Warn, str(R.string.pp_no_contact), str(R.string.pp_no_contact_d))
            },
        )
        add(
            if (says(text, "delet", "retention", "retain", "erase")) {
                Check(Status.Pass, str(R.string.pp_deletion))
            } else {
                Check(Status.Warn, str(R.string.pp_no_deletion), str(R.string.pp_no_deletion_d))
            },
        )
        if (r == null) return@buildList
        val missing = buildList {
            if (hasAds(r) && !says(text, "advertis", "admob", " ads ")) add(Msg.Raw("Ads"))
            if (sdksOf(r, SdkKind.Analytics).isNotEmpty() && !says(text, "analytic")) add(Msg.Raw("Analytics"))
            if (sdksOf(r, SdkKind.Crashes).isNotEmpty() && !says(text, "crash")) add(Msg.Raw("Crash reports"))
            if (LOCATION.any { r.requests(it) } && !says(text, "location")) add(Msg.Raw("Location"))
            if (r.requests(P + "CAMERA") && !says(text, "camera", "photo")) add(Msg.Raw("Camera"))
            if (r.requests(P + "RECORD_AUDIO") && !says(text, "microphone", "audio", "voice")) add(Msg.Raw("Microphone"))
            if (r.requests(P + "READ_CONTACTS") && !says(text, "contact")) add(Msg.Raw("Contacts"))
            if (sells(r) && !says(text, "purchase", "payment", "billing", "subscription")) add(Msg.Raw("Purchases"))
            if (DeprecatedApis.signsIn(r) && !says(text, "account", "sign in", "sign-in", "login")) add(Msg.Raw("Accounts"))
        }
        add(
            if (missing.isEmpty()) {
                Check(Status.Pass, str(R.string.pp_covers))
            } else {
                Check(Status.Warn, str(R.string.pp_gaps), str(R.string.pp_gaps_d), missing)
            },
        )
        add(Check(Status.Info, str(R.string.lc_english), str(R.string.pp_english_d)))
    }

    /** Under this many characters of text, the page is a stub or drawn by script. */
    private const val SHORT_POLICY = 600

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** Visible text from HTML: scripts, styles and tags gone, entities for spaces and ampersands. */
    fun text(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&rsquo;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ---- Conditions: network, offline, low memory -----------------------------------

    private fun has(r: ApkReport, artifact: String) = r.libraries.keys.any { it.startsWith(artifact) }

    fun network(r: ApkReport): List<Check> = buildList {
        if (!r.requests(P + "INTERNET")) {
            add(Check(Status.Pass, str(R.string.nt_none)))
            return@buildList
        }
        add(
            if (r.requests(P + "ACCESS_NETWORK_STATE")) {
                Check(Status.Pass, str(R.string.nt_state))
            } else {
                Check(Status.Warn, str(R.string.nt_no_state), str(R.string.nt_no_state_d))
            },
        )
        add(
            if (r.allowsCleartext) {
                Check(Status.Warn, str(R.string.nt_cleartext), str(R.string.nt_cleartext_d))
            } else {
                Check(Status.Pass, str(R.string.nt_https))
            },
        )
        if (r.manifest.networkSecurityConfig) add(Check(Status.Info, str(R.string.nt_config), str(R.string.nt_config_d)))
        if (has(r, "androidx.work:")) add(Check(Status.Pass, str(R.string.nt_work), str(R.string.nt_work_d)))
        add(Check(Status.Info, str(R.string.nt_test), str(R.string.nt_test_d)))
    }

    fun offline(r: ApkReport): List<Check> = buildList {
        if (!r.requests(P + "INTERNET")) {
            add(Check(Status.Pass, str(R.string.of_none)))
            return@buildList
        }
        val local = listOf("androidx.room:", "androidx.datastore:", "androidx.sqlite:").filter { has(r, it) }
        add(
            if (local.isNotEmpty()) {
                Check(Status.Pass, str(R.string.of_store), str(R.string.of_store_d), local.map { Msg.Raw(it.removeSuffix(":")) })
            } else {
                Check(Status.Info, str(R.string.of_no_store), str(R.string.of_no_store_d))
            },
        )
        if (has(r, "androidx.work:")) add(Check(Status.Pass, str(R.string.of_work)))
        add(Check(Status.Info, str(R.string.of_test), str(R.string.of_test_d)))
    }

    fun lowMemory(r: ApkReport): List<Check> = buildList {
        if (r.largeHeap) add(Check(Status.Warn, str(R.string.lm_large_heap), str(R.string.lm_large_heap_d)))
        if (has(r, "androidx.lifecycle:lifecycle-viewmodel-savedstate") || has(r, "androidx.savedstate:")) {
            add(Check(Status.Pass, str(R.string.lm_saved_state), str(R.string.lm_saved_state_d)))
        }
        val handled = r.componentList.filter { it.kind == ComponentKind.Activity && it.configChanges != 0 }
        if (handled.isNotEmpty()) {
            add(
                Check(
                    Status.Info,
                    str(R.string.lm_config),
                    str(R.string.lm_config_d),
                    handled.take(MAX_EVIDENCE).map { Msg.Raw(it.name.substringAfterLast('.')) },
                ),
            )
        }
        add(Check(Status.Info, str(R.string.lm_test), str(R.string.lm_test_d)))
    }

    private const val MAX_EVIDENCE = 8
}
