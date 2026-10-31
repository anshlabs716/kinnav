package org.geonav.family.crypto

import android.content.SharedPreferences
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic core for KinNav (ported from Luogo-FOSS).
 *
 * Design rules enforced here:
 *  - Authentication, item identity, group keys and transport encryption are kept separate.
 *  - Device identity is an Ed25519 keypair. The private seed never leaves the device.
 *  - Group payloads are sealed with AES-256-GCM under a per-group symmetric key.
 *  - Passwords are never used as keys. Keys come from [SecureRandom].
 *  - Every ciphertext is bound to a context string (AAD), so a ciphertext produced for one
 *    group can never be replayed into another.
 *
 * Ed25519 comes from Bouncy Castle because Android Keystore cannot generate Ed25519 keys
 * below API 33. AES-GCM comes from the platform JCE (available since API 23).
 */
class CryptoEngine(private val prefs: SharedPreferences? = null) {

    data class DeviceIdentity(
        val publicKeyBytes: ByteArray,
        val privateKeySeed: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class InvitePayload(
        val groupId: String,
        val inviteToken: String,
        val groupKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val random = SecureRandom()

    /** In-memory fallback so the engine is unit-testable without Android preferences. */
    private val memoryKeys = HashMap<String, ByteArray>()
    private val memoryIdentityLock = Any()
    private var memoryIdentity: DeviceIdentity? = null

    // ---------------------------------------------------------------- identity

    fun loadOrCreateDeviceIdentity(): DeviceIdentity {
        val existingSeed = readKey(KEY_DEVICE_SEED)
        val existingPub = readKey(KEY_DEVICE_PUB)
        if (existingSeed != null && existingPub != null) {
            return DeviceIdentity(existingPub, existingSeed)
        }
        synchronized(memoryIdentityLock) {
            memoryIdentity?.let { if (readKey(KEY_DEVICE_SEED) == null) return it }
            val seed = ByteArray(32).also(random::nextBytes)
            // Ed25519 clamped seed expansion via Bouncy Castle.
            val priv = Ed25519PrivateKeyParameters(seed, 0)
            val pub = priv.generatePublicKey().encoded
            val identity = DeviceIdentity(pub, seed)
            writeKey(KEY_DEVICE_SEED, seed)
            writeKey(KEY_DEVICE_PUB, pub)
            memoryIdentity = identity
            return identity
        }
    }

    /** Stable, non-reversible id derived from the device public key. */
    fun deviceIdFor(publicKey: ByteArray): String =
        "u_" + encodeBase64Url(sha256(publicKey)).take(22)

    fun signDetached(message: ByteArray, privateKeySeed: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeySeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verifyDetached(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // An Ed25519 key is always exactly 32 bytes; reject anything else before parsing.
        if (signature.size != 64 || publicKey.size != 32) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ keys

    fun generateKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun getOrCreateGroupKey(groupId: String): ByteArray =
        readKey(groupKeyPrefKey(groupId)) ?: generateKey().also { writeKey(groupKeyPrefKey(groupId), it) }

    fun importGroupKey(groupId: String, key: ByteArray) {
        require(key.size == 32) { "Group key must be 32 bytes" }
        writeKey(groupKeyPrefKey(groupId), key)
    }

    fun hasGroupKey(groupId: String): Boolean = readKey(groupKeyPrefKey(groupId)) != null

    fun forgetGroupKey(groupId: String) {
        val storageKey = groupKeyPrefKey(groupId)
        prefs?.edit()?.remove(storageKey)?.apply()
        synchronized(memoryKeys) { memoryKeys.remove(storageIdOf(storageKey)) }
    }

    // ------------------------------------------------------------ encryption

    /** Seals [cleartext] for [groupId]. Output is `nonce || ciphertext || tag`. */
    fun encryptForGroup(groupId: String, cleartext: ByteArray): ByteArray =
        encryptWithKey(getOrCreateGroupKey(groupId), cleartext, aad = groupId)

    fun decryptForGroup(groupId: String, ciphertext: ByteArray): ByteArray? {
        val key = readKey(groupKeyPrefKey(groupId)) ?: return null
        return decryptWithKey(key, ciphertext, aad = groupId)
    }

    fun encryptWithKey(key: ByteArray, cleartext: ByteArray, aad: String = ""): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32 byte key" }
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(cleartext)
        return nonce + body
    }

    /** Returns null when the key is wrong or the tag does not verify. Never throws. */
    fun decryptWithKey(key: ByteArray, ciphertext: ByteArray, aad: String = ""): ByteArray? {
        if (key.size != 32 || ciphertext.size <= GCM_NONCE_BYTES) return null
        return try {
            val nonce = ciphertext.copyOfRange(0, GCM_NONCE_BYTES)
            val body = ciphertext.copyOfRange(GCM_NONCE_BYTES, ciphertext.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(body)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deterministic keyed hash used for BLE identifier derivation.
     * [info] is domain-separated so the same seed cannot produce the same id in two roles.
     */
    fun deriveKey(seed: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal(info.toByteArray(Charsets.UTF_8)).copyOf(length.coerceAtMost(32))
    }

    // ---------------------------------------------------------------- invites

    /**
     * Wire format: `geonav-invite-key: v1:<groupId>:<inviteToken>:<base64url group key>`.
     *
     * The legacy `luogo-invite-key:` prefix is still accepted on parse so invite codes
     * minted by Luogo-FOSS can be pasted into KinNav and vice versa on a shared relay.
     */
    fun buildInvitePayload(groupId: String, inviteToken: String, groupKey: ByteArray): String =
        "$INVITE_PREFIX $INVITE_VERSION:$groupId:$inviteToken:${encodeBase64Url(groupKey)}"

    fun parseInvitePayload(raw: String): InvitePayload? {
        val trimmed = raw.trim()
        val body = when {
            trimmed.startsWith("$INVITE_PREFIX ") -> trimmed.removePrefix("$INVITE_PREFIX ")
            trimmed.startsWith("$LEGACY_INVITE_PREFIX ") -> trimmed.removePrefix("$LEGACY_INVITE_PREFIX ")
            else -> return null
        }
        val parts = body.split(':')
        if (parts.size != 4) return null
        if (parts[0] != INVITE_VERSION) return null
        val groupId = parts[1]
        val token = parts[2]
        if (groupId.isBlank() || token.isBlank()) return null
        val key = try {
            decodeBase64Url(parts[3])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (key.size != 32) return null
        return InvitePayload(groupId, token, key)
    }

    // ---------------------------------------------------------------- helpers

    private fun groupKeyPrefKey(groupId: String) = "$KEY_GROUP_PREFIX$groupId"

    private fun readKey(storageKey: String): ByteArray? {
        prefs?.let { p ->
            val encoded = p.getString(storageKey, null) ?: return@let
            return try {
                decodeBase64Url(encoded)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        synchronized(memoryKeys) { return memoryKeys[storageIdOf(storageKey)]?.copyOf() }
    }

    private fun writeKey(storageKey: String, value: ByteArray) {
        prefs?.edit()?.putString(storageKey, encodeBase64Url(value))?.apply()
        synchronized(memoryKeys) { memoryKeys[storageIdOf(storageKey)] = value.copyOf() }
    }

    /**
     * Preferences keys are the group ids themselves, but a caller could pass something that
     * collides with our reserved prefixes. Prefixing every stored key keeps them disjoint.
     */
    private fun storageIdOf(storageKey: String) = "$KEY_STORAGE_NAMESPACE$storageKey"

    companion object {
        const val INVITE_PREFIX = "geonav-invite-key:"
        const val LEGACY_INVITE_PREFIX = "luogo-invite-key:"
        const val INVITE_VERSION = "v1"

        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private const val KEY_STORAGE_NAMESPACE = "k."
        private const val KEY_DEVICE_SEED = "device.identity.seed"
        private const val KEY_DEVICE_PUB = "device.identity.public"
        private const val KEY_GROUP_PREFIX = "group.key."

        /**
         * Base64url without padding.
         *
         * Deliberately uses Bouncy Castle rather than `android.util.Base64`, because the
         * platform class is a stub under JVM unit tests and returns null there, which would
         * make the crypto untestable. `java.util.Base64` is not an option either: it needs
         * API 26 and shared invite payloads must stay byte-compatible across devices.
         */
        fun encodeBase64Url(bytes: ByteArray): String =
            Base64.toBase64String(bytes)
                .replace("\n", "")
                .replace("\r", "")
                .trimEnd('=')
                .replace('+', '-')
                .replace('/', '_')

        fun decodeBase64Url(value: String): ByteArray {
            val standard = value.replace('-', '+').replace('_', '/')
            // Bouncy Castle's decoder wants the padding back.
            val padded = when (standard.length % 4) {
                0 -> standard
                2 -> standard + "=="
                3 -> standard + "="
                else -> throw IllegalArgumentException("Not valid base64url")
            }
            return Base64.decode(padded)
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}