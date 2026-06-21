package io.github.nitanmarcel.jdex.project

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexDetectTest {

    private fun dexHeader(): ByteArray {
        val b = ByteArray(0x70)
        byteArrayOf(0x64, 0x65, 0x78, 0x0A).copyInto(b)
        "035".toByteArray(Charsets.US_ASCII).copyInto(b, 4); b[7] = 0
        b[0x24] = 0x70
        b[0x28] = 0x78; b[0x29] = 0x56; b[0x2a] = 0x34; b[0x2b] = 0x12
        return b
    }

    @Test
    fun validDexDetected() {
        val b = dexHeader()
        assertTrue(Dex.isDex(b))
        assertTrue(Dex.looksLikeDex(b))
    }

    @Test
    fun mangledMagicStillDetectedByEndianTag() {
        val b = dexHeader()
        b[0] = 0; b[1] = 0; b[2] = 0; b[3] = 0
        b[0x24] = 0
        assertFalse(Dex.isDex(b))
        assertTrue(Dex.looksLikeDex(b), "endian_tag should still flag it as a dex")
    }

    @Test
    fun mangledMagicStillDetectedByHeaderSize() {
        val b = dexHeader()
        b[0] = 0x7f; b[1] = 'E'.code.toByte()
        b[0x28] = 0; b[0x29] = 0; b[0x2a] = 0; b[0x2b] = 0
        assertTrue(Dex.looksLikeDex(b), "header_size 0x70 should still flag it as a dex")
    }

    @Test
    fun randomBlobIsNotDex() {
        val b = ByteArray(0x80) { (it * 7 + 3).toByte() }
        assertFalse(Dex.looksLikeDex(b))
    }

    @Test
    fun tooSmallIsNotDex() {
        assertFalse(Dex.looksLikeDex(ByteArray(0x10)))
    }
}
