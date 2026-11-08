package org.geonav.family.finding

import org.geonav.family.crypto.CryptoEngine

/**
 * Rotating-identifier cryptography for item finding.
 *
 * An item's BLE advertisement carries ONLY a rotating pseudonym — never a stable
 * address, never the item key. The pseudonym is
 * `HMAC-SHA256(itemKey, "kinnav.finding.id.v1:" + epochHour)` truncated to 16 bytes and
 * hex-encoded (32 hex chars, which is exactly what the relay's
 * `KotlinRelayServerEngine.isValidRotatingId` accepts). It rotates every hour, so
 * tracking an item across time requires the key, and the key never leaves the owner.
 *
 * Owner and advertiser derive the same id for the same epoch; helpers see only the id
 * and report sightings of it. The relay stores sightings keyed on the full rotating id
 * and cannot relate two epochs of the same item.
 */
object FindingCrypto {

    private val engine = CryptoEngine()

    private const val ID_INFO_PREFIX = "kinnav.finding.id.v1:"
    private const val ID_BYTES = 16
    const val ROTATING_ID_HEX_LENGTH = 32

    /** One hour. All devices agree on the epoch by wall clock, as Luogo's did. */
    const val EPOCH_MS: Long = 3_600_000L

    /** The hex alphabet the relay validates against (lowercase or uppercase). */
    private val HEX = "0123456789abcdef".toCharArray()

    fun currentEpochHour(nowMs: Long = System.currentTimeMillis()): Long = nowMs / EPOCH_MS

    /** Rotating id for [epochHour], as 32 lowercase hex chars. */
    fun rotatingIdHex(itemKey: ByteArray, epochHour: Long): String {
        require(itemKey.size == 32) { "Item key must be 32 bytes" }
        val derived = engine.deriveKey(itemKey, "$ID_INFO_PREFIX$epochHour", ID_BYTES)
        return hexOf(derived)
    }

    /** The advertisement payload for [epochHour]: the raw 16 rotating-id bytes. */
    fun rotatingIdBytes(itemKey: ByteArray, epochHour: Long): ByteArray {
        val hex = rotatingIdHex(itemKey, epochHour)
        return ByteArray(ID_BYTES) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }

    /** Mirrors the relay's format check, so a malformed id is dropped before the network is touched. */
    fun isValidRotatingId(value: String): Boolean =
        value.length == ROTATING_ID_HEX_LENGTH && value.all { Character.digit(it, 16) >= 0 }

    /** Advertisement company id. 0xFFFF is the Bluetooth SIG's reserved test value —
     *  deliberately not any registered vendor's, so KinNav beacons never masquerade
     *  as another company's products. */
    const val MANUFACTURER_ID: Int = 0xFFFF

    fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    fun bytesOf(hex: String): ByteArray {
        require(isValidRotatingId(hex)) { "Not a valid rotating id" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }
}
