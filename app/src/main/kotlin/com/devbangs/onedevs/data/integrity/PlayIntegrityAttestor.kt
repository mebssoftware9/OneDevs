package com.devbangs.onedevs.data.integrity

import android.content.Context
import com.devbangs.onedevs.data.backend.Attestor
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Play's word that this is the real OneDevs, as Play distributes it,
 * on a genuine phone: a Standard Play Integrity token, bound to a hash the
 * server checks.
 *
 * The token provider is prepared once and kept. Google Play can invalidate
 * it, so a request that fails prepares a fresh one and tries once more.
 * Anything that goes wrong is a null: the server, not the app, decides what a
 * session without a verdict may do.
 */
class PlayIntegrityAttestor(context: Context, private val cloudProject: Long) : Attestor {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val lock = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun prepare() {
        provider(fresh = false)
    }

    override suspend fun token(requestHash: String): String? {
        val ready = provider(fresh = false) ?: return null
        return request(ready, requestHash) ?: provider(fresh = true)?.let { request(it, requestHash) }
    }

    private suspend fun request(ready: StandardIntegrityTokenProvider, requestHash: String): String? =
        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                ready.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                    .await()
                    .token()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private suspend fun provider(fresh: Boolean): StandardIntegrityTokenProvider? = lock.withLock {
        if (cloudProject <= 0L) return@withLock null
        if (!fresh) provider?.let { return@withLock it }
        val prepared = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                manager.prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProject).build(),
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        provider = prepared
        prepared
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
