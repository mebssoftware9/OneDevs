package com.devbangs.onedevs.data.play

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What asking Play about a package established. None of these is a statement
 * about Google's own records of a closed test -- only about whether a public
 * store listing answers.
 */
sealed interface PlayListing {
    /** A public listing exists. The app is live. */
    data object Live : PlayListing

    /**
     * No public listing.
     *
     * Consistent with a closed test, and equally consistent with a package that
     * was never published or was typed wrong: Play answers both with the same
     * 404 and the same 1673-byte body. This is never on its own enough to call
     * something a closed test.
     */
    data object NotPublic : PlayListing

    /** The question did not get an answer worth acting on. */
    data class Unknown(val status: Int?) : PlayListing
}

/**
 * Asks Play whether a package has a public listing.
 *
 * A HEAD request for the status code and nothing else. No body is transferred
 * and nothing from the response is read, parsed or stored -- the distinction
 * between this and scraping a listing is the whole reason it is allowed to
 * exist in this codebase.
 *
 * On-device this is advisory only. A modified build can claim any answer it
 * likes, so a listing is not trustworthy until a server has asked the same
 * question itself.
 */
object PlayListings {

    /**
     * Regions turned out not to move this -- a live listing answered 200 from
     * US, GB and SL alike -- but the parameter stays explicit rather than
     * implicit in whatever the device happens to be.
     */
    suspend fun check(
        packageName: String,
        region: String = "US",
        timeoutMs: Int = 10_000,
    ): PlayListing = withContext(Dispatchers.IO) {
        val url = URL(
            "https://play.google.com/store/apps/details" +
                "?id=$packageName&gl=$region&hl=en",
        )
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
            }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> PlayListing.Live
                HttpURLConnection.HTTP_NOT_FOUND -> PlayListing.NotPublic
                else -> PlayListing.Unknown(status)
            }
        } catch (_: Exception) {
            PlayListing.Unknown(null)
        } finally {
            connection?.disconnect()
        }
    }

    internal const val USER_AGENT = "OneDevs/0.1 (+https://github.com/mebssoftware9/OneDevs)"
}
