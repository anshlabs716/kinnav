package org.geonav.family.finding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindingItemCodecTest {

    private val item = FindingItem(
        id = "item-1",
        name = "House keys",
        keyBase64 = "abc123",
        createdAtMs = 1_700_000_000_000,
        isLost = true,
        lastSeenAtMs = 1_700_000_100_000,
        lastSeenLatitude = 52.52,
        lastSeenLongitude = 13.405,
        lastSeenAccuracyMeters = 12f,
        lastSeenRssiDbm = -67
    )

    @Test
    fun `encode then decode round trips every field`() {
        val decoded = FindingItemCodec.decode(FindingItemCodec.encode(listOf(item)))
        assertEquals(listOf(item), decoded)
    }

    @Test
    fun `decode of persisted defaults keeps lost mode`() {
        val plain = FindingItem(id = "i", name = "n", keyBase64 = "k", createdAtMs = 0L)
        val decoded = FindingItemCodec.decode(FindingItemCodec.encode(listOf(plain)))
        assertEquals(false, decoded.single().isLost)
        assertNull(decoded.single().lastSeenAtMs)
    }

    @Test
    fun `decode of garbage yields an empty list instead of crashing`() {
        assertTrue(FindingItemCodec.decode("not json at all").isEmpty())
        assertTrue(FindingItemCodec.decode("").isEmpty())
        assertTrue(FindingItemCodec.decode("[{\"id\":123}]").isEmpty())
    }
}
