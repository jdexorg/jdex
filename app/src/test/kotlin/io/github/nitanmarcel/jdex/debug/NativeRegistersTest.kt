package io.github.nitanmarcel.jdex.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeRegistersTest {
    @Test fun parsesRegisterInfoLine() {
        val r = parseRegisterInfo("name:x0;bitsize:64;offset:0;encoding:uint;format:hex;set:General;")!!
        assertEquals("x0", r.name); assertEquals(64, r.bitsize); assertEquals(0, r.offset)
    }

    @Test fun parseReturnsNullOnError() {
        assertNull(parseRegisterInfo("E45"))
    }

    @Test fun parsesMissingOffsetAsSentinel() {
        val r = parseRegisterInfo("name:x0;bitsize:64;encoding:uint;format:hex;set:General;generic:arg1;")!!
        assertEquals(-1, r.offset)
    }

    @Test fun assignOffsetsFillsCumulativeWhenMissing() {
        val out = assignOffsets(listOf(RegInfo("x0", 64, -1), RegInfo("x1", 64, -1), RegInfo("cpsr", 32, -1)))
        assertEquals(listOf(0, 8, 16), out.map { it.offset })
    }

    @Test fun decodesLittleEndianGPacket() {
        val regs = listOf(RegInfo("x0", 64, 0), RegInfo("x1", 64, 8))
        val g = "0100000000000000" + "ff00000000000000"
        assertEquals(listOf("x0" to 1uL, "x1" to 255uL), decodeG(regs, g))
    }
}
