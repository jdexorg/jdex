package io.github.nitanmarcel.jdex.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RspTest {
    @Test fun checksumMatchesKnownPacket() {
        assertEquals("9a", rspChecksum("OK"))
        assertEquals("b0", rspChecksum("QStartNoAckMode"))
    }

    @Test fun frameWrapsWithDollarHashChecksum() {
        assertEquals("\$OK#9a", rspFrame("OK"))
    }

    @Test fun unframeVerifiesChecksum() {
        assertEquals("OK", rspUnframe("\$OK#9a"))
        assertNull(rspUnframe("\$OK#00"))
    }

    @Test fun escapeRoundTrips() {
        val raw = "a}b\$c#d*e"
        assertEquals(raw, rspUnescape(rspEscape(raw)))
    }
}
