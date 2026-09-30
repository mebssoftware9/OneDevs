package com.devbangs.onedevs.data.backend

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A response, or the absence of one.
 *
 * [code] 0 means the request never reached a server -- no signal, DNS failure,
 * timeout. It is deliberately not an exception: on this network that is an
 * ordinary Tuesday, and callers should decide what it means rather than have
 * the stack unwound out from under them.
 */
internal data class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    val offline: Boolean get() = code == 0
}

/**
 * What a call came back with, sorted by what the caller should do next.
 *
 * The distinction that matters is between "ask again later" and "asking again
 * will not change the answer". Collapsing them -- every failure a null -- is
 * how a refusal ended up retried forever while the phone showed coins it was
 * never going to get.
 */
sealed interface Reply<out T> {
    /** The server answered, and here is what it said. */
    data class Answer<T>(val value: T) : Reply<T>

    /**
     * Nothing useful arrived: no signal, a timeout, a gateway error, a server
     * too busy to answer. The question still stands and is worth asking again.
     */
    data class Unreachable(val detail: String) : Reply<Nothing>

    /** No one is signed in any more. Hold the question until someone is. */
    data object SignedOut : Reply<Nothing>

    /**
     * The server understood the request and refused it in a way repeating
     * will not change: a bad argument, a missing function, a reply that cannot
     * be read.
     */
    data class Rejected(val code: Int, val detail: String) : Reply<Nothing>
}

/**
 * Sorts a raw response. Pure, so the rules are pinned by tests rather than
 * rediscovered on a train.
 *
 * 408, 425 and 429 are the server asking for patience; 5xx is the server or
 * the gateway in front of it having a bad moment. Both are worth retrying.
 * Every other 4xx is an answer, even if it is not the one wanted.
 */
internal fun classify(result: HttpResult): Reply<String> = when {
    result.ok -> Reply.Answer(result.body)
    result.offline -> Reply.Unreachable(result.body)
    result.code == 401 -> Reply.SignedOut
    result.code == 408 || result.code == 425 || result.code == 429 -> Reply.Unreachable("HTTP ${result.code}")
    result.code >= 500 -> Reply.Unreachable("HTTP ${result.code}")
    else -> Reply.Rejected(result.code, result.body.take(300))
}

/**
 * Marks work nobody is waiting on: a refresh behind data already on screen, a
 * claim retried by WorkManager, a balance re-read. Requests made inside it
 * never raise "Slow connection", because nothing on screen is stuck.
 *
 * The notice exists to explain a wait. Shown for background traffic it
 * explained nothing and read as the app being broken.
 */
class QuietRequest : kotlin.coroutines.AbstractCoroutineContextElement(QuietRequest) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<QuietRequest>
}

/** Runs [block] as background traffic when [quiet] is true. */
suspend fun <T> quietly(quiet: Boolean = true, block: suspend () -> T): T =
    if (quiet) kotlinx.coroutines.withContext(QuietRequest()) { block() } else block()

/** The same request, carrying bytes. Used for uploads, where a String is wrong. */
internal suspend fun httpUpload(
    url: String,
    method: String,
    headers: Map<String, String>,
    bytes: ByteArray,
    contentType: String,
    timeoutMs: Int = 30_000,
): HttpResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: IOException) {
        HttpResult(0, e.message.orEmpty())
    } finally {
        connection?.disconnect()
    }
}

internal suspend fun httpRequest(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    timeoutMs: Int = 15_000,
): HttpResult = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        // errorStream carries the body on 4xx and 5xx, and PostgREST puts the
        // reason there. Reading only inputStream would throw away every
        // explanation the server offered.
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: IOException) {
        HttpResult(0, e.message.orEmpty())
    } finally {
        connection?.disconnect()
    }
}
