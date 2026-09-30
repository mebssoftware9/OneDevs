package com.devbangs.onedevs.data.plans

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import com.devbangs.onedevs.data.backend.quietly
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Prices as Play formats them for this person's country, or null if unknown. */
data class Offers(
    val monthly: String? = null,
    val yearly: String? = null,
    val ghostline: String? = null,
)

/** How a purchase ended, for the screen that started it. */
sealed interface PurchaseOutcome {
    data object ProActive : PurchaseOutcome
    data object GhostlineStarted : PurchaseOutcome

    /** Paid, but Play has not settled it yet (cash, bank transfer). */
    data object Pending : PurchaseOutcome

    data object Cancelled : PurchaseOutcome

    /** Paid and kept by Play; OneDevs could not confirm it yet. Retried on launch. */
    data object Unconfirmed : PurchaseOutcome

    /** OneDevs refused it: [reason] is the server's word, e.g. already_running. */
    data class Refused(val reason: String?) : PurchaseOutcome

    data object Unavailable : PurchaseOutcome
}

@Serializable
private data class VerifyReply(val ok: Boolean = false, val reason: String? = null)

/**
 * Google Play Billing, reduced to what OneDevs sells.
 *
 * The phone never decides what was bought. Every purchase goes to the
 * play-purchases function, which asks Google, and only a yes from there
 * grants anything. A purchase whose confirmation is lost to a bad connection
 * is still in Play's list, so it is sent again on the next launch.
 *
 * Ghostline is consumable -- a developer can run it again for another app --
 * so it is consumed only after the server has started the run.
 */
class Billing(
    context: Context,
    private val backend: Backend,
    private val account: AccountState,
    private val plans: PlanStore,
    private val scope: CoroutineScope,
) {
    private val _offers = MutableStateFlow(Offers())
    val offers: StateFlow<Offers> = _offers.asStateFlow()

    private val _outcomes = MutableSharedFlow<PurchaseOutcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<PurchaseOutcome> = _outcomes.asSharedFlow()

    private var details: Map<String, ProductDetails> = emptyMap()
    private val connecting = Mutex()
    private val verifying = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch {
            account.session.collect { session ->
                if (session != null) {
                    loadOffers()
                    restore()
                }
            }
        }
    }

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Reads prices from Play. */
    suspend fun loadOffers() {
        if (!ready()) return
        val subs = query(Products.LAB_PRO, BillingClient.ProductType.SUBS)
        val once = query(Products.GHOSTLINE, BillingClient.ProductType.INAPP)
        details = listOfNotNull(subs, once).associateBy { it.productId }
        _offers.value = Offers(
            monthly = subs?.priceOf(Products.MONTHLY),
            yearly = subs?.priceOf(Products.YEARLY),
            ghostline = once?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice,
        )
    }

    private suspend fun query(id: String, type: String): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(type)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { _, result ->
                if (cont.isActive) cont.resume(result.productDetailsList.firstOrNull())
            }
        }
    }

    private fun ProductDetails.priceOf(basePlan: String): String? =
        subscriptionOfferDetails
            ?.filter { it.basePlanId == basePlan }
            // The base plan itself, not a trial or intro offer on top of it.
            ?.minByOrNull { it.pricingPhases.pricingPhaseList.size }
            ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice

    /** Opens Play's sheet for Lab Pro on [basePlan]. */
    suspend fun buyPro(activity: Activity, basePlan: String): Boolean {
        val me = account.session.value?.userId ?: return false
        if (!ready()) return false
        if (details.isEmpty()) loadOffers()
        val product = details[Products.LAB_PRO] ?: return false
        val offer = product.subscriptionOfferDetails
            ?.filter { it.basePlanId == basePlan }
            ?.minByOrNull { it.pricingPhases.pricingPhaseList.size }
            ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .setObfuscatedAccountId(me)
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Opens Play's sheet for a Ghostline run on [listingId]. The listing
     * travels inside the purchase, where the server reads it from Google.
     */
    suspend fun buyGhostline(activity: Activity, listingId: String): Boolean {
        val me = account.session.value?.userId ?: return false
        if (!ready()) return false
        if (details.isEmpty()) loadOffers()
        val product = details[Products.GHOSTLINE] ?: return false
        val offer = product.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        // A one-time product with a single offer may have no
                        // token; Play then uses that offer.
                        .apply { offer.offerToken?.let { setOfferToken(it) } }
                        .build(),
                ),
            )
            .setObfuscatedAccountId(me)
            .setObfuscatedProfileId(listingId)
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { settle(it, loud = true) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _outcomes.tryEmit(PurchaseOutcome.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore() }
            else -> _outcomes.tryEmit(PurchaseOutcome.Unavailable)
        }
    }

    /** Sends every purchase Play still holds to the server. Quiet. */
    suspend fun restore() {
        if (account.session.value == null || !ready()) return
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val owned = suspendCancellableCoroutine { cont ->
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { _, list ->
                    if (cont.isActive) cont.resume(list)
                }
            }
            owned.forEach { settle(it, loud = false) }
        }
        plans.refresh()
    }

    private suspend fun settle(purchase: Purchase, loud: Boolean) = verifying.withLock {
        val product = purchase.products.firstOrNull() ?: return@withLock
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            if (loud) _outcomes.tryEmit(PurchaseOutcome.Pending)
            return@withLock
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@withLock
        // A subscription already confirmed needs no second trip on launch;
        // renewals reach the server from Google directly.
        if (!loud && product == Products.LAB_PRO && purchase.isAcknowledged) return@withLock

        val kind = if (product == Products.GHOSTLINE) "inapp" else "subs"
        val result = quietly(quiet = !loud) {
            backend.function(
                "play-purchases",
                buildJsonObject {
                    put("kind", JsonPrimitive(kind))
                    put("productId", JsonPrimitive(product))
                    put("purchaseToken", JsonPrimitive(purchase.purchaseToken))
                },
            )
        }
        val reply = try {
            if (result.body.isBlank()) null else BackendJson.decodeFromString(VerifyReply.serializer(), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        val outcome = when {
            reply?.ok == true && kind == "inapp" -> {
                consume(purchase.purchaseToken)
                PurchaseOutcome.GhostlineStarted
            }
            reply?.ok == true -> PurchaseOutcome.ProActive
            reply?.reason == "pending" -> PurchaseOutcome.Pending
            // The server answered no: the run could not start (already
            // running on that app, not your app). Play refunds a purchase
            // that is never acknowledged.
            reply != null && result.code in 400..499 && reply.reason != "unknown_purchase" ->
                PurchaseOutcome.Refused(reply.reason)
            reply != null && result.ok -> PurchaseOutcome.Refused(reply.reason)
            else -> PurchaseOutcome.Unconfirmed
        }
        if (outcome == PurchaseOutcome.ProActive || outcome == PurchaseOutcome.GhostlineStarted) plans.refresh()
        if (loud) _outcomes.tryEmit(outcome)
    }

    private suspend fun consume(token: String) {
        suspendCancellableCoroutine { cont ->
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { _, _ ->
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
