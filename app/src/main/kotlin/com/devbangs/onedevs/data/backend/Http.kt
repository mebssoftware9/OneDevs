package com.devbangs.onedevs.data.backend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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

/**
 * One client for the whole app.
 *
 * HttpURLConnection opened a connection per call; a phone far from the
 * server paid DNS, TCP and a TLS handshake -- several round trips -- before
 * the first byte of every request. One OkHttp client keeps connections open
 * in a pool and speaks HTTP/2, so requests to Supabase share one warm
 * connection, and the Board's parallel fallback reads multiplex over it.
 */
internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Idle connections stay warm for five minutes: long enough to cover a
        // trip to the Play Store and back, which is the app's whole rhythm.
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /** A client with other timeouts that still shares the pool. */
    fun withTimeout(ms: Int): OkHttpClient =
        if (ms == 15_000) client
        else client.newBuilder()
            .readTimeout(ms.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(ms.toLong(), TimeUnit.MILLISECONDS)
            .build()
}

private val NO_BODY_METHODS = setOf("GET", "HEAD", "DELETE")

/**
 * Runs [request] and turns every outcome into an [HttpResult]: code 0 for
 * anything that never reached a server. Cancelling the coroutine cancels the
 * call, so a screen closed mid-request stops using the network.
 */
private suspend fun execute(client: OkHttpClient, request: Request): HttpResult =
    suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(HttpResult(0, e.message.orEmpty()))
            }

            override fun onResponse(call: Call, response: Response) {
                // The body is read for errors too: PostgREST explains itself
                // in the body of a 4xx, and dropping it drops the reason.
                val result = try {
                    response.use { HttpResult(it.code, it.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    HttpResult(0, e.message.orEmpty())
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

private fun build(url: String, method: String, headers: Map<String, String>, body: RequestBody?): Request? {
    val builder = try {
        Request.Builder().url(url)
    } catch (e: IllegalArgumentException) {
        return null
    }
    headers.forEach { (name, value) -> builder.header(name, value) }
    val payload = body ?: if (method in NO_BODY_METHODS) null else ByteArray(0).toRequestBody(null)
    return builder.method(method, payload).build()
}

/** The same request, carrying bytes. Used for uploads, where a String is wrong. */
internal suspend fun httpUpload(
    url: String,
    method: String,
    headers: Map<String, String>,
    bytes: ByteArray,
    contentType: String,
    timeoutMs: Int = 30_000,
): HttpResult {
    val request = build(url, method, headers, bytes.toRequestBody(contentType.toMediaTypeOrNull()))
        ?: return HttpResult(0, "bad url")
    return execute(Http.withTimeout(timeoutMs), request)
}

internal suspend fun httpRequest(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    timeoutMs: Int = 15_000,
): HttpResult {
    val payload = body?.toRequestBody(JSON)
    val request = build(url, method, headers, payload) ?: return HttpResult(0, "bad url")
    return execute(Http.withTimeout(timeoutMs), request)
}

private val JSON = "application/json".toMediaType()
