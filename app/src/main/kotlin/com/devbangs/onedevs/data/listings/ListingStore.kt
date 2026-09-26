package com.devbangs.onedevs.data.listings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * ignoreUnknownKeys because a listing written by a later build will be read by
 * this one after a downgrade or a restore, and losing the whole file over a
 * field this version has never heard of would be the worst possible trade.
 */
internal val ListingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal object ListingsSerializer : Serializer<Listings> {
    override val defaultValue = Listings()

    override suspend fun readFrom(input: InputStream): Listings =
        try {
            ListingJson.decodeFromString(Listings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Stored listings could not be read", e)
        }

    override suspend fun writeTo(t: Listings, output: OutputStream) {
        output.write(ListingJson.encodeToString(Listings.serializer(), t).encodeToByteArray())
    }
}

/**
 * The apps this developer has put up.
 *
 * Local, and local is the truth for now: these are the developer's own records
 * of their own apps. What is not local truth is anything the platform owes them
 * for it -- DevCoins above all -- which is why no balance lives here.
 */
interface ListingRepository {
    val listings: Flow<List<Listing>>
    suspend fun add(listing: Listing)
    suspend fun remove(id: String)
}

class DataStoreListingRepository(
    private val store: DataStore<Listings>,
) : ListingRepository {

    override val listings: Flow<List<Listing>> = store.data.map { it.items }

    override suspend fun add(listing: Listing) {
        store.updateData { it.withAdded(listing) }
    }

    override suspend fun remove(id: String) {
        store.updateData { it.without(id) }
    }
}
