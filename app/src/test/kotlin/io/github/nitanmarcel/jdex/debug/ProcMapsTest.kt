package io.github.nitanmarcel.jdex.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProcMapsTest {
    private val maps = """
        7f8a3c1000-7f8a3c5000 r--p 00000000 fd:00 100 /data/app/x/lib/arm64/libjdexdbg.so
        7f8a3c5000-7f8a3c9000 r-xp 00004000 fd:00 100 /data/app/x/lib/arm64/libjdexdbg.so
        7f8a3c9000-7f8a3ca000 rw-p 00008000 fd:00 100 /data/app/x/lib/arm64/libjdexdbg.so
        7f0000000000-7f0000001000 rw-p 00000000 00:00 0 [anon:something]
    """.trimIndent()

    @Test fun pickBaseAtFileOffsetZeroAndSpan() {
        val m = parseMaps(maps).single { it.path.endsWith("libjdexdbg.so") }
        assertEquals(0x7f8a3c1000L, m.base)
        assertEquals(0x7f8a3ca000L, m.end)
    }

    @Test fun resolvePcToVaddr() {
        val r = ModuleResolver(parseMaps(maps))
        assertEquals("libjdexdbg.so" to 0x4010L, r.resolve(0x7f8a3c5010L))
        assertNull(r.resolve(0x10L))
        assertEquals(0x7f8a3c5010L, r.runtimeAddr("libjdexdbg.so", 0x4010L))
    }
}
