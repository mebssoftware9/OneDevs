package com.devbangs.onedevs.data.listings

import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * The developer's own listings, kept by the server.
 *
 * Local storage was the truth while a listing was a private note to yourself.
 * The moment the Board shows other people's apps it stops being one: a listing
 * is a public offer to testers, and an offer that only exists on the phone that
 * made it is not an offer.
 *
 * The flow holds what was last read rather than what was last written, so a
 * failed write shows the old row instead of a row that exists nowhere.
 */
class RemoteListingRepository(
    private val backend: Backend,
    private val account: AccountState,
    scope: CoroutineScope,
) : ListingRepository {

    private val _mine = MutableStateFlow<List<Listing>>(emptyList())
    override val listings: Flow<List<Listing>> = _mine.asStateFlow()

    init {
        // Signing in loads them; signing out empties them. Neither screen has
        // to remember to ask.
        scope.launch {
            account.session.collect { refresh() }
        }
    }

    suspend fun refresh() = com.devbangs.onedevs.data.backend.quietly { refreshNow() }

    private suspend fun refreshNow() {
        val owner = account.session.value?.userId
        if (owner == null) {
            _mine.value = emptyList()
            return
        }
        val result = backend.rest(
            "listings?owner=eq.$owner&select=*&order=created_at.desc",
        )
        if (result.ok) _mine.value = decode(result.body)
    }

    override suspend fun add(listing: Listing) {
        val owner = account.session.value?.userId ?: return
        val body = ListingJson.encodeToString(
            ListingWrite.serializer(),
            listing.toWrite(owner),
        )
        val result = backend.rest(
            // on_conflict=id so editing updates the row rather than failing or
            // duplicating. The unique (owner, package_name, channel) index is
            // the other guard: the same app twice on one board is a mistake.
            path = "listings?on_conflict=id",
            method = "POST",
            body = body,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        if (result.ok) refresh()
    }

    override suspend fun remove(id: String) {
        val result = backend.rest("listings?id=eq.$id", method = "DELETE")
        if (result.ok) refresh()
    }

    override suspend fun find(id: String): Listing? {
        _mine.value.firstOrNull { it.id == id }?.let { return it }
        // Not one of ours: someone else's listing, opened from the Board.
        val result = backend.rest("listings?id=eq.$id&select=*")
        return if (result.ok) decode(result.body).firstOrNull() else null
    }

    /**
     * Every app on a board, yours included.
     *
     * Hiding your own was wrong: a developer needs to see their listing sitting
     * among the others, and watch it move down as newer apps arrive and
     * disappear when they can no longer fund a test. A board you cannot find
     * yourself on tells you nothing about how you are doing on it.
     *
     * Testing your own app is still refused, but that belongs to the button,
     * not to the list.
     *
     * board_listings rather than listings: the view drops any app whose owner
     * can no longer pay, because a row that cannot pay wastes a tester's
     * thirty-two seconds and then refuses them.
     */
    suspend fun board(channel: Channel, device: String, limit: Int = 50): List<Listing> =
        boardOrNull(channel, device, limit).orEmpty()

    /** [board], but null when it could not be read, so a failure never looks like an empty board. */
    suspend fun boardOrNull(channel: Channel, device: String, limit: Int = 50): List<Listing>? {
        val name = if (channel == Channel.Live) "live" else "testing"
        // board() also drops apps this phone has already been paid for under
        // any account. Without it someone signed into a second account sees an
        // app, does the test, and is refused at the end -- which from where
        // they sit is indistinguishable from being cheated.
        val fitted = backend.rpc(
            "board",
            kotlinx.serialization.json.buildJsonObject {
                put("p_channel", kotlinx.serialization.json.JsonPrimitive(name))
                put("p_device", kotlinx.serialization.json.JsonPrimitive(device))
                put("p_limit", kotlinx.serialization.json.JsonPrimitive(limit))
            },
        )
        if (fitted.ok) return decodeOrNull(fitted.body)
        // A database without board() yet: the plain view still works, and the
        // test screen catches the device rule before anyone starts.
        val result = backend.rest(
            "board_listings?channel=eq.$name&select=*&order=created_at.desc&limit=$limit",
        )
        return if (result.ok) decodeOrNull(result.body) else null
    }

    /** Listing rows as the server sends them, from any endpoint that returns the listings shape. */
    internal fun decodeOrNull(body: String): List<Listing>? = try {
        ListingJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ListingRow.serializer()),
            body,
        ).map { it.toListing() }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun decode(body: String): List<Listing> = try {
        ListingJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ListingRow.serializer()),
            body,
        ).map { it.toListing() }
    } catch (e: SerializationException) {
        emptyList()
    }
}
