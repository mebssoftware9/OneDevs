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
