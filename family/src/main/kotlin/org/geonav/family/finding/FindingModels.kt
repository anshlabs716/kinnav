package org.geonav.family.finding

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A trackable item (keys, bike, luggage…) the user wants to be able to find again.
 *
 * The device that owns the item advertises a rotating BLE identifier derived from
 * [FindingItem.keyBase64]; any KinNav device with "help others find" enabled that sees
 * the advertisement reports a sighting to the relay, which stores it keyed on the
 * rotating id. Only the owner (who holds the key) can derive the rotating ids, so only
 * the owner knows which sightings belong to which item — the relay never learns either.
 *
 * Everything here is stored locally in SharedPreferences as JSON (see [FindingItemCodec]);
 * no item key ever leaves the device.
 */
@Serializable
data class FindingItem(
    val id: String,
    val name: String,
    /** 32 random bytes, base64url. Root of the rotating-id derivation chain. */
    val keyBase64: String,
    val createdAtMs: Long,
    /** Lost mode: this device starts advertising the item's rotating id. */
    val isLost: Boolean = false,
    // Last confirmed sighting (owner-side, filled by locate()). Null until seen.
    val lastSeenAtMs: Long? = null,
    val lastSeenLatitude: Double? = null,
    val lastSeenLongitude: Double? = null,
    val lastSeenAccuracyMeters: Float? = null,
    val lastSeenRssiDbm: Int? = null
)

/** A helper's report that it saw a device advertising [rotatingBleIdHex]. */
data class SightingReport(
    val reportId: String,
    val rotatingBleIdHex: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val rssiDbm: Int,
    val timestampMs: Long
)

/** The relay's answer to "where was this item last seen". */
data class LatestSighting(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val rssiDbm: Int,
    val timestampMs: Long
)

/**
 * JSON (de)serialisation of the item list, split out of [FindingRepository] so the
 * persistence layer stays unit-testable on the JVM without Android SharedPreferences.
 */
object FindingItemCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(items: List<FindingItem>): String =
        json.encodeToString(ListSerializer(FindingItem.serializer()), items)

    fun decode(raw: String): List<FindingItem> = runCatching {
        json.decodeFromString(ListSerializer(FindingItem.serializer()), raw)
    }.getOrDefault(emptyList())
}
