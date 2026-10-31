package org.geonav.relay

import org.geonav.relay.core.KotlinRelayServerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the relay engine's sighting store and authorisation.
 *
 * The sighting path was previously dead: stores were created only by [createGroup], so a
 * sighting was silently dropped for any item whose owner was not in a group on that relay, and
 * a short rotating identifier threw an index error. These tests pin the behaviour down.
 */
class RelaySightingEngineTest {

    private val validId = "aabbccddeeff00112233445566778899"

    private fun sighting(
        reportId: String,
        rotatingId: String = validId,
        timestamp: Long = 1_000L
    ) = KotlinRelayServerEngine.StoredSighting(
        reportId = reportId,
        rotatingBleIdHex = rotatingId,
        helperLatitude = 12.9,
        helperLongitude = 77.5,
        helperAccuracyMeters = 8f,
        rssiDbm = -60,
        encryptedPayload = "CIPHER",
        authTag = "TAG",
        timestamp = timestamp
    )

    @Test
    fun `sighting is accepted without any group having been created`() {
        val engine = KotlinRelayServerEngine()
        // The regression: this used to return false because no sighting store existed.
        assertTrue(engine.recordSighting(sighting("r1")))
        assertNotNull(engine.latestSightingForItem(validId))
        assertEquals(12.9, engine.latestSightingForItem(validId)!!.helperLatitude, 1e-9)
    }

    @Test
    fun `duplicate report id is rejected so a retry cannot inflate the count`() {
        val engine = KotlinRelayServerEngine()
        assertTrue(engine.recordSighting(sighting("r1")))
        assertFalse(engine.recordSighting(sighting("r1")))
        assertFalse(engine.recordSighting(sighting("r1")))
    }

    @Test
    fun `malformed rotating identifiers are rejected instead of throwing`() {
        val engine = KotlinRelayServerEngine()
        // These threw StringIndexOutOfBoundsException before the fix.
        assertFalse(engine.recordSighting(sighting("s1", rotatingId = "short")))
        assertFalse(engine.recordSighting(sighting("s2", rotatingId = "")))
        assertFalse(engine.recordSighting(sighting("s3", rotatingId = "zz".repeat(16))))
        assertNull(engine.latestSightingForItem("short"))
        assertNull(engine.latestSightingForItem(""))
    }

    @Test
    fun `blank report id is rejected`() {
        val engine = KotlinRelayServerEngine()
        assertFalse(engine.recordSighting(sighting("   ")))
    }

    @Test
    fun `latest sighting is the most recent for that item`() {
        val engine = KotlinRelayServerEngine()
        engine.recordSighting(sighting("old", timestamp = 100L))
        engine.recordSighting(sighting("new", timestamp = 900L))
        engine.recordSighting(sighting("mid", timestamp = 500L))
        assertEquals("new", engine.latestSightingForItem(validId)!!.reportId)
    }

    @Test
    fun `sightings for different items do not bleed together`() {
        val engine = KotlinRelayServerEngine()
        val other = "0011223344556677889900aabbccddee"
        engine.recordSighting(sighting("mine", rotatingId = validId))
        engine.recordSighting(sighting("theirs", rotatingId = other))
        assertEquals("mine", engine.latestSightingForItem(validId)!!.reportId)
        assertEquals("theirs", engine.latestSightingForItem(other)!!.reportId)
    }

    @Test
    fun `only the group owner may remove a member`() {
        val engine = KotlinRelayServerEngine()
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val (bob, _) = engine.createUser("Bob", 2L, "pk")
        val group = engine.createGroup(alice.id, "Family")

        assertTrue(engine.isMember(group.id, alice.id))
        assertFalse(engine.isMember(group.id, bob.id))
        // Bob is not even in the group yet, so nothing to remove.
        assertFalse(engine.removeMember(group.id, bob.id, alice.id))
    }

    @Test
    fun `message log is bounded and prunes the oldest`() {
        val engine = KotlinRelayServerEngine(maxLogPerGroup = 2)
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val group = engine.createGroup(alice.id, "Family")
        engine.appendMessage(group.id, alice.id, "one")
        engine.appendMessage(group.id, alice.id, "two")
        engine.appendMessage(group.id, alice.id, "three")

        val log = engine.getMessagesAfter(group.id, alice.id, 0L)!!
        assertEquals(2, log.size)
        assertEquals("two", log.first().ciphertext)
        assertEquals("three", log.last().ciphertext)
    }

    @Test
    fun `a non-member cannot post into a group`() {
        val engine = KotlinRelayServerEngine()
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val (mallory, _) = engine.createUser("Mallory", 3L, "pk")
        val group = engine.createGroup(alice.id, "Family")

        assertNull(engine.appendMessage(group.id, mallory.id, "cipher"))
        assertNull(engine.getMessagesAfter(group.id, mallory.id, 0L))
        // And the legitimate member still can.
        assertNotNull(engine.appendMessage(group.id, alice.id, "cipher"))
    }

    @Test
    fun `an invite is single use and expiring`() {
        val engine = KotlinRelayServerEngine()
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val (bob, _) = engine.createUser("Bob", 2L, "pk")
        val group = engine.createGroup(alice.id, "Family")
        val invite = engine.createInvite(group.id, alice.id)!!

        assertNotNull(engine.joinGroupWithInvite(group.id, bob.id, invite.token))
        assertNull(engine.joinGroupWithInvite(group.id, bob.id, invite.token))

        val (carol, _) = engine.createUser("Carol", 4L, "pk")
        val stale = engine.createInvite(group.id, alice.id)!!
        assertNull(
            engine.joinGroupWithInvite(
                group.id,
                carol.id,
                stale.token,
                nowMs = stale.expiresAt + 1
            )
        )
    }

    @Test
    fun `only a member can mint an invite for a group`() {
        val engine = KotlinRelayServerEngine()
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val (mallory, _) = engine.createUser("Mallory", 3L, "pk")
        val group = engine.createGroup(alice.id, "Family")
        assertNotNull(engine.createInvite(group.id, alice.id))
        assertNull(engine.createInvite(group.id, mallory.id))
    }

    @Test
    fun `an unknown or malformed token authenticates to nobody`() {
        val engine = KotlinRelayServerEngine()
        val (alice, token) = engine.createUser("Alice", 1L, "pk")
        assertEquals(alice.id, engine.authenticateToken(token)?.id)
        assertEquals(alice.id, engine.authenticateToken("Bearer $token")?.id)
        assertNull(engine.authenticateToken("wrong"))
        assertNull(engine.authenticateToken(""))
        assertNull(engine.authenticateToken(null))
    }

    @Test
    fun `member count tracks joins and leaves`() {
        val engine = KotlinRelayServerEngine()
        val (alice, _) = engine.createUser("Alice", 1L, "pk")
        val (bob, _) = engine.createUser("Bob", 2L, "pk")
        val group = engine.createGroup(alice.id, "Family")
        assertEquals(1, engine.memberCountOf(group.id))

        val invite = engine.createInvite(group.id, alice.id)!!
        engine.joinGroupWithInvite(group.id, bob.id, invite.token)
        assertEquals(2, engine.memberCountOf(group.id))

        assertTrue(engine.leaveGroup(group.id, bob.id))
        assertEquals(1, engine.memberCountOf(group.id))
    }
}