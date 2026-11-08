package org.geonav.family.finding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotating-id cryptography for item finding. The relay's format rule (32 hex chars) is
 * mirrored in [FindingCrypto.isValidRotatingId]; these tests pin that contract from the
 * client side so a drift breaks the build, not a lost child's beacon.
 */
class FindingCryptoTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `rotating id is 32 hex characters`() {
        val id = FindingCrypto.rotatingIdHex(key, epochHour = 12345L)
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `same key and epoch always produce the same id`() {
        val a = FindingCrypto.rotatingIdHex(key, epochHour = 42L)
        val b = FindingCrypto.rotatingIdHex(key.copyOf(), epochHour = 42L)
        assertEquals(a, b)
    }

    @Test
    fun `a new epoch rotates the id`() {
        val now = FindingCrypto.rotatingIdHex(key, epochHour = 1000L)
        val next = FindingCrypto.rotatingIdHex(key, epochHour = 1001L)
        assertTrue("ids must rotate per epoch", now != next)
    }

    @Test
    fun `a different key produces a different id`() {
        val otherKey = ByteArray(32) { (it * 7).toByte() }
        assertTrue(
            FindingCrypto.rotatingIdHex(key, 7L) != FindingCrypto.rotatingIdHex(otherKey, 7L)
        )
    }

    @Test
    fun `hex and bytes round trip`() {
        val id = FindingCrypto.rotatingIdHex(key, 999L)
        val bytes = FindingCrypto.rotatingIdBytes(key, 999L)
        assertEquals(16, bytes.size)
        assertEquals(id, FindingCrypto.hexOf(bytes))
        assertTrue(FindingCrypto.bytesOf(id).contentEquals(bytes))
    }

    @Test
    fun `id validation mirrors the relay format rule`() {
        val id = FindingCrypto.rotatingIdHex(key, 5L)
        assertTrue(FindingCrypto.isValidRotatingId(id))
        assertTrue(FindingCrypto.isValidRotatingId(id.uppercase()))
        assertFalse(FindingCrypto.isValidRotatingId(id.dropLast(1)))
        assertFalse(FindingCrypto.isValidRotatingId(id + "0"))
        assertFalse(FindingCrypto.isValidRotatingId("g".repeat(32)))
        assertFalse(FindingCrypto.isValidRotatingId(""))
    }

    @Test
    fun `epochs are one hour wide`() {
        assertEquals(3_600_000L, FindingCrypto.EPOCH_MS)
        assertEquals(5L, FindingCrypto.currentEpochHour(5L * 3_600_000L + 1L))
        assertEquals(6L, FindingCrypto.currentEpochHour(6L * 3_600_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short item key is rejected`() {
        FindingCrypto.rotatingIdHex(ByteArray(16), 1L)
    }
}
