package org.geonav.family.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the sharing crypto core (ported from Luogo's CryptoEngineTest).
 *
 * All of these run on the JVM: CryptoEngine falls back to an in-memory store when no
 * SharedPreferences are supplied, so the tests exercise the real primitives without an
 * Android device.
 */
class CryptoEngineTest {

    private fun engine() = CryptoEngine(prefs = null)

    // ------------------------------------------------------------- identity

    @Test
    fun `device identity is stable within a session`() {
        val crypto = engine()
        val first = crypto.loadOrCreateDeviceIdentity()
        val second = crypto.loadOrCreateDeviceIdentity()
        assertArrayEquals(first.publicKeyBytes, second.publicKeyBytes)
        assertArrayEquals(first.privateKeySeed, second.privateKeySeed)
    }

    @Test
    fun `device id is derived deterministically from the public key`() {
        val crypto = engine()
        val identity = crypto.loadOrCreateDeviceIdentity()
        val idA = crypto.deviceIdFor(identity.publicKeyBytes)
        val idB = crypto.deviceIdFor(identity.publicKeyBytes)
        assertEquals(idA, idB)
        assertTrue(idA.startsWith("u_"))
    }

    @Test
    fun `different public keys give different device ids`() {
        val crypto = engine()
        val a = crypto.deviceIdFor(ByteArray(32) { 1 })
        val b = crypto.deviceIdFor(ByteArray(32) { 2 })
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------- sign / verify

    @Test
    fun `signature round trip verifies`() {
        val crypto = engine()
        val identity = crypto.loadOrCreateDeviceIdentity()
        val message = "location payload".toByteArray()
        val signature = crypto.signDetached(message, identity.privateKeySeed)
        assertEquals(64, signature.size)
        assertTrue(crypto.verifyDetached(message, signature, identity.publicKeyBytes))
    }

    @Test
    fun `tampered message fails verification`() {
        val crypto = engine()
        val identity = crypto.loadOrCreateDeviceIdentity()
        val message = "location payload".toByteArray()
        val signature = crypto.signDetached(message, identity.privateKeySeed)
        val tampered = "location payloaD".toByteArray()
        assertFalse(crypto.verifyDetached(tampered, signature, identity.publicKeyBytes))
    }

    @Test
    fun `wrong public key fails verification`() {
        val crypto = engine()
        val identity = crypto.loadOrCreateDeviceIdentity()
        val other = crypto.generateKey() // 32 random bytes, not the signer's key
        val message = "payload".toByteArray()
        val signature = crypto.signDetached(message, identity.privateKeySeed)
        assertFalse(crypto.verifyDetached(message, signature, other))
    }

    @Test
    fun `malformed signature or key is rejected without throwing`() {
        val crypto = engine()
        val identity = crypto.loadOrCreateDeviceIdentity()
        assertFalse(crypto.verifyDetached("m".toByteArray(), ByteArray(10), identity.publicKeyBytes))
        assertFalse(crypto.verifyDetached("m".toByteArray(), ByteArray(64), ByteArray(10)))
    }

    // ---------------------------------------------------------- encryption

    @Test
    fun `group encryption round trip`() {
        val crypto = engine()
        val groupId = "grp-test"
        val plaintext = """{"lat":52.5,"lon":13.4}""".toByteArray()
        val ciphertext = crypto.encryptForGroup(groupId, plaintext)
        val decrypted = crypto.decryptForGroup(groupId, ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `ciphertext from one group cannot be read by another`() {
        val crypto = engine()
        val plaintext = "secret".toByteArray()
        val ciphertext = crypto.encryptForGroup("grp-a", plaintext)
        crypto.getOrCreateGroupKey("grp-b")
        // decryptForGroup binds the AAD to the group id, so even a copied key refuses.
        assertNull(crypto.decryptWithKey(crypto.getOrCreateGroupKey("grp-b"), ciphertext, aad = "grp-b"))
    }

    @Test
    fun `decryption with the right key but wrong aad fails`() {
        val crypto = engine()
        val key = crypto.generateKey()
        val ciphertext = crypto.encryptWithKey(key, "data".toByteArray(), aad = "group-a")
        assertNotNull(crypto.decryptWithKey(key, ciphertext, aad = "group-a"))
        assertNull(crypto.decryptWithKey(key, ciphertext, aad = "group-b"))
    }

    @Test
    fun `garbage ciphertext returns null instead of throwing`() {
        val crypto = engine()
        val key = crypto.generateKey()
        assertNull(crypto.decryptWithKey(key, ByteArray(8) { 0x42 }))
        assertNull(crypto.decryptWithKey(ByteArray(5), ByteArray(64)))
    }

    @Test
    fun `same plaintext encrypts to different ciphertexts (fresh nonce)`() {
        val crypto = engine()
        val plaintext = "same".toByteArray()
        val a = crypto.encryptForGroup("grp-nonce", plaintext)
        val b = crypto.encryptForGroup("grp-nonce", plaintext)
        assertFalse(a.contentEquals(b))
    }

    // --------------------------------------------------------- group keys

    @Test
    fun `group key is created once and reused`() {
        val crypto = engine()
        val first = crypto.getOrCreateGroupKey("grp-k")
        val second = crypto.getOrCreateGroupKey("grp-k")
        assertArrayEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun `importing a group key replaces the stored one`() {
        val crypto = engine()
        crypto.getOrCreateGroupKey("grp-imp")
        val fresh = crypto.generateKey()
        crypto.importGroupKey("grp-imp", fresh)
        assertArrayEquals(fresh, crypto.getOrCreateGroupKey("grp-imp"))
    }

    @Test
    fun `forgetting a group key removes it`() {
        val crypto = engine()
        crypto.getOrCreateGroupKey("grp-gone")
        assertTrue(crypto.hasGroupKey("grp-gone"))
        crypto.forgetGroupKey("grp-gone")
        assertFalse(crypto.hasGroupKey("grp-gone"))
        assertNull(crypto.decryptForGroup("grp-gone", ByteArray(32)))
    }

    // ------------------------------------------------------------- invites

    @Test
    fun `invite payload round trip`() {
        val crypto = engine()
        val key = crypto.generateKey()
        val payload = crypto.buildInvitePayload("grp-1", "tok-abc", key)
        assertTrue(payload.startsWith(CryptoEngine.INVITE_PREFIX))
        val parsed = crypto.parseInvitePayload(payload)
        assertNotNull(parsed)
        assertEquals("grp-1", parsed!!.groupId)
        assertEquals("tok-abc", parsed.inviteToken)
        assertArrayEquals(key, parsed.groupKey)
    }

    @Test
    fun `legacy luogo invite prefix is still accepted`() {
        val crypto = engine()
        val key = crypto.generateKey()
        val legacy = crypto.buildInvitePayload("grp-2", "tok", key)
            .replace(CryptoEngine.INVITE_PREFIX, CryptoEngine.LEGACY_INVITE_PREFIX)
        val parsed = crypto.parseInvitePayload(legacy)
        assertNotNull(parsed)
        assertEquals("grp-2", parsed!!.groupId)
        assertArrayEquals(key, parsed.groupKey)
    }

    @Test
    fun `malformed invites are rejected`() {
        val crypto = engine()
        assertNull(crypto.parseInvitePayload(""))
        assertNull(crypto.parseInvitePayload("not-an-invite"))
        assertNull(crypto.parseInvitePayload("${CryptoEngine.INVITE_PREFIX} v1:only:three"))
        assertNull(crypto.parseInvitePayload("${CryptoEngine.INVITE_PREFIX} v9:g:t:key"))
        assertNull(crypto.parseInvitePayload("${CryptoEngine.INVITE_PREFIX} v1::t:key"))
        assertNull(crypto.parseInvitePayload("${CryptoEngine.INVITE_PREFIX} v1:g::key"))
    }

    // -------------------------------------------------------------- derive

    @Test
    fun `deriveKey is deterministic and domain separated`() {
        val crypto = engine()
        val seed = crypto.generateKey()
        val a1 = crypto.deriveKey(seed, "info-a")
        val a2 = crypto.deriveKey(seed, "info-a")
        val b = crypto.deriveKey(seed, "info-b")
        assertArrayEquals(a1, a2)
        assertFalse(a1.contentEquals(b))
    }

    // ------------------------------------------------------------- base64

    @Test
    fun `base64url round trip handles binary`() {
        val data = byteArrayOf(0, 1, -1, 63, 64, -128)
        val encoded = CryptoEngine.encodeBase64Url(data)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
        assertArrayEquals(data, CryptoEngine.decodeBase64Url(encoded))
    }
}
