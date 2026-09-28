package com.devbangs.onedevs.data.listings

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A listing as the database holds it.
 *
 * Kept apart from [Listing] on purpose. The domain model is shaped for the
 * screens that read it; this one is shaped for Postgres columns, and bending
 * either into the other means every future change to one drags the other with
 * it.
 */
@Serializable
internal data class ListingRow(
    val id: String,
    val owner: String? = null,
    @SerialName("package_name") val packageName: String,
    val title: String,
    val category: String,
    val channel: String,
    /** What one test of this app pays. The server sets it; the client reads it. */
    val reward: Int = 0,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("test_note") val testNote: String? = null,
    @SerialName("play_url") val playUrl: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("public_listing") val publicListing: Boolean? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Postgres sends "2026-09-28T03:04:05.123456+00:00". Instant.parse refuses an
 * offset in that form; OffsetDateTime reads it and then it is a timestamp.
 */
private fun String?.toEpochMillis(): Long = this?.let {
    try {
        OffsetDateTime.parse(it).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
} ?: 0L

private fun Long.toIsoOrNull(): String? =
    if (this <= 0L) null else Instant.ofEpochMilli(this).toString()

internal fun ListingRow.toListing(): Listing = Listing(
    id = id,
    packageName = packageName,
    title = title,
    category = category,
    channel = if (channel == "live") Channel.Live else Channel.Testing,
    reward = reward,
    optInLink = playUrl,
    testNote = testNote,
    iconUrl = iconUrl,
    sizeBytes = sizeBytes,
    createdAt = createdAt.toEpochMillis(),
    check = CheckRecord(
        publicListing = publicListing,
        checkedAt = checkedAt.toEpochMillis(),
    ),
)

/**
 * What gets written back.
 *
 * Its own shape rather than reusing the read row, for two reasons that pull in
 * opposite directions. created_at is absent entirely: a client that could set
 * it could pin its own listing to the top of the board forever. Everything
 * else nullable is sent *explicitly*, including nulls -- so clearing a note
 * clears it, which omitting the field would not do on an upsert.
 */
@Serializable
internal data class ListingWrite(
    val id: String,
    val owner: String,
    @SerialName("package_name") val packageName: String,
    val title: String,
    val category: String,
    val channel: String,
    @SerialName("size_bytes") val sizeBytes: Long?,
    @SerialName("test_note") val testNote: String?,
    @SerialName("play_url") val playUrl: String?,
    @SerialName("icon_url") val iconUrl: String?,
    @SerialName("public_listing") val publicListing: Boolean?,
    @SerialName("checked_at") val checkedAt: String?,
)

internal fun Listing.toWrite(owner: String): ListingWrite = ListingWrite(
    id = id,
    owner = owner,
    packageName = packageName,
    title = title,
    category = category,
    channel = if (channel == Channel.Live) "live" else "testing",
    sizeBytes = sizeBytes,
    testNote = testNote,
    playUrl = optInLink,
    iconUrl = iconUrl,
    publicListing = check.publicListing,
    checkedAt = check.checkedAt.toIsoOrNull(),
)
