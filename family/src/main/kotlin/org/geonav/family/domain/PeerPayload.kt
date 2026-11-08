package org.geonav.family.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire format of an encrypted group location broadcast.
 *
 * This replaces the previous regex scraping of the decrypted plaintext: payloads are now
 * parsed as strict JSON (unknown keys tolerated, everything else validated by the
 * serializer), so a malformed or hostile plaintext can no longer smuggle half-truths
 * through — it either parses completely or is dropped whole.
 *
 * Field names match the outbound encoder in FamilyRepository byte-for-byte and are
 * unchanged from Luogo-FOSS, so Luogo and KinNav clients in one group keep working.
 */
@Serializable
data class PeerPayload(
    val id: String? = null,
    val name: String? = null,
    val lat: Double,
    val lon: Double,
    val ts: Long,
    val acc: Float? = null,
    val bat: Int? = null,
    val color: Long? = null,
    val spd: Float? = null,
    val brg: Float? = null,
    val act: String? = null,
    val src: String? = null
) {
    /** Sanity bounds; positions outside these are corrupt, not merely inaccurate. */
    fun isSane(): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0 &&
            (acc == null || acc >= 0f) &&
            (bat == null || bat in 0..100)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Returns null for anything that is not a complete, sane location payload. */
        fun parse(text: String): PeerPayload? =
            runCatching { json.decodeFromString(serializer(), text) }
                .getOrNull()
                ?.takeIf { it.isSane() }
    }
}
