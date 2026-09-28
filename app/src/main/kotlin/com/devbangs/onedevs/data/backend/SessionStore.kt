package com.devbangs.onedevs.data.backend

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * The session on disk.
 *
 * Wrapped in a record rather than stored as a bare nullable so that "signed
 * out" is a file containing {} rather than the absence of a file, which is
 * also what a failed first write looks like.
 *
 * This holds a refresh token in the app's private storage. That is the same
 * protection every Android app gives its credentials -- the sandbox -- and it
 * is worth being plain that a rooted device can read it.
 */
@Serializable
data class StoredSession(val session: Session? = null)

object SessionSerializer : Serializer<StoredSession> {
    override val defaultValue = StoredSession()

    override suspend fun readFrom(input: InputStream): StoredSession =
        try {
            BackendJson.decodeFromString<StoredSession>(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("session.json could not be read", e)
        }

    override suspend fun writeTo(t: StoredSession, output: OutputStream) {
        output.write(BackendJson.encodeToString(t).toByteArray())
    }
}

class DataStoreSessionHolder(
    private val store: DataStore<StoredSession>,
) : SessionHolder {
    override suspend fun current(): Session? = store.data.first().session

    override suspend fun save(session: Session?) {
        store.updateData { StoredSession(session) }
    }
}
