package com.devbangs.onedevs.data.wallet

import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.backend.classify
import com.devbangs.onedevs.data.tests.decode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One movement of DevCoins, named after what it was for. */
@Serializable
data class WalletEntry(
    val at: String = "",
    val delta: Int = 0,
    val reason: String = "",
    val title: String? = null,
)

/**
 * What the server says this developer has.
 *
 * [held] is promised to testers who are part way through testing this
 * developer's listings: still in the balance, no longer spendable. Null
 * when the reply did not say, so the wallet shows "–" rather than a
 * confident 0 and an available figure that counts promised coins.
 */
@Serializable
data class Wallet(
    val balance: Int = 0,
    val held: Int? = null,
    val entries: List<WalletEntry> = emptyList(),
) {
    val available: Int? get() = held?.let { (balance - it).coerceAtLeast(0) }
}

class WalletRepository(private val backend: Backend) {
    suspend fun load(limit: Int = 100): Reply<Wallet> = decode(
        classify(backend.rpc("wallet", buildJsonObject { put("p_limit", JsonPrimitive(limit)) })),
        Wallet.serializer(),
    )
}

/**
 * The line a ledger reason reads as. Each takes the entry's title where it
 * has one -- the app tested, the mission joined -- because "test_reward" is
 * a column value, not a sentence.
 */
fun entryLabel(reason: String): Int = when (reason) {
    "grant" -> R.string.wallet_reason_grant
    "test_reward", "ghostline_reward", "spotlight_reward" -> R.string.wallet_reason_test_reward
    "plan_bonus" -> R.string.wallet_reason_plan_bonus
    "test_payment" -> R.string.wallet_reason_test_payment
    "mission_entry" -> R.string.wallet_reason_mission_entry
    "mission_day", "mission_bonus" -> R.string.wallet_reason_mission_reward
    "mission_refund", "listing_refund" -> R.string.wallet_reason_refund
    else -> R.string.wallet_reason_other
}
