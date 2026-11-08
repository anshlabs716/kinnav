package org.geonav.family.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbound payload parser replaced regex scraping with strict JSON. These tests pin
 * the contract: the exact outbound wire format parses, anything malformed or insane is
 * dropped whole, and unknown keys are tolerated for forward compatibility.
 */
class PeerPayloadTest {

    /** Byte-for-byte what FamilyRepository.broadcastEncryptedLocationToGroups emits. */
    private val outbound = """
        {"name":"Mom","id":"u_abc","lat":52.52,"lon":13.405,"acc":12.0,"ts":1700000000000,
         "spd":1.5,"brg":270.0,"color":4283384525,"bat":87,"act":"WALKING","src":"GNSS"}
    """.trimIndent()

    @Test
    fun `the outbound wire format parses completely`() {
        val payload = PeerPayload.parse(outbound)
        assertEquals(52.52, payload!!.lat, 1e-9)
        assertEquals(13.405, payload.lon, 1e-9)
        assertEquals(1_700_000_000_000L, payload.ts)
        assertEquals("Mom", payload.name)
        assertEquals("u_abc", payload.id)
        assertEquals(12f, payload.acc)
        assertEquals(87, payload.bat)
        assertEquals("WALKING", payload.act)
        assertEquals("GNSS", payload.src)
        assertTrue(payload.isSane())
    }

    @Test
    fun `malformed json is dropped whole`() {
        assertNull(PeerPayload.parse("lat is 52.52 and lon is 13.4"))
        assertNull(PeerPayload.parse("{\"lat\":52.5,\"lon\":13.4")) // truncated
        assertNull(PeerPayload.parse(""))
    }

    @Test
    fun `missing required fields is dropped`() {
        assertNull(PeerPayload.parse("{\"lat\":52.5,\"lon\":13.4}")) // no ts
        assertNull(PeerPayload.parse("{\"name\":\"x\"}"))
    }

    @Test
    fun `impossible coordinates are rejected`() {
        assertNull(
            PeerPayload.parse(
                "{\"lat\":999.0,\"lon\":13.4,\"ts\":1}"
            )
        )
        assertNull(
            PeerPayload.parse(
                "{\"lat\":52.5,\"lon\":-9999.0,\"ts\":1}"
            )
        )
    }

    @Test
    fun `impossible sensor values are rejected`() {
        assertNull(PeerPayload.parse("{\"lat\":52.5,\"lon\":13.4,\"ts\":1,\"bat\":200}"))
        assertNull(PeerPayload.parse("{\"lat\":52.5,\"lon\":13.4,\"ts\":1,\"acc\":-5.0}"))
    }

    @Test
    fun `unknown keys are tolerated for forward compatibility`() {
        val payload = PeerPayload.parse(
            "{\"lat\":52.5,\"lon\":13.4,\"ts\":1,\"futureField\":{\"nested\":[1,2]}}"
        )
        assertEquals(52.5, payload!!.lat, 1e-9)
    }

    @Test
    fun `neutral battery and accuracy pass`() {
        val payload = PeerPayload.parse("{\"lat\":-33.9,\"lon\":151.2,\"ts\":1}")
        assertTrue(payload!!.isSane())
        assertFalse(payload.lat > 0)
    }
}
