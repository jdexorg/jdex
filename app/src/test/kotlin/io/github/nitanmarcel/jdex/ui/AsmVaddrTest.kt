package io.github.nitanmarcel.jdex.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AsmVaddrTest {
    @Test fun parsesLeadingOffset() {
        assertEquals(0x4010L, parseAsmVaddr("00004010: add w0, w0, w1"))
    }
    @Test fun ignoresNonInstructionLines() {
        assertNull(parseAsmVaddr("loc_4010:"))
        assertNull(parseAsmVaddr("; Java native: nativeAdd"))
    }
}
