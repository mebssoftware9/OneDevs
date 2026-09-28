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

    suspend fun refresh() {
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
     * Everyone else's apps on a board. Excluding your own is not a courtesy:
     * testing your own app proves nothing, and join_mission refuses it anyway.
     */
    suspend fun board(channel: Channel, limit: Int = 50): List<Listing> {
        val owner = account.session.value?.userId ?: return emptyList()
        val name = if (channel == Channel.Live) "live" else "testing"
        // board_listings, not listings: the view drops any app whose owner can
        // no longer pay for a test. A row that cannot pay would waste a
        // tester's thirty-two seconds and then refuse them.
        val result = backend.rest(
            "board_listings?channel=eq.$name&owner=neq.$owner" +
                "&select=*&order=created_at.desc&limit=$limit",
        )
        return if (result.ok) decode(result.body) else emptyList()
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
