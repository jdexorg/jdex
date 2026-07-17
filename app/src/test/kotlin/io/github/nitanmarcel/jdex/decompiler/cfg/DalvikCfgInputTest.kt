package io.github.nitanmarcel.jdex.decompiler.cfg

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue

class DalvikCfgInputTest {
    private fun source(): DexInputSource {
        val bytes = DalvikCfgInputTest::class.java.getResourceAsStream("/fixtures/opaque.dex")!!.readBytes()
        val dex = File.createTempFile("opaque", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        return DexInputSource.load(dex)
    }

    @Test fun `every method converts and offsets are preserved`() {
        val src = source()
        val methods = src.allMethods().filter { it.insns.isNotEmpty() }
        assertTrue(methods.isNotEmpty())
        for (m in methods) {
            val cfgInsns = DalvikCfgInput.toCfgInsns(m)
            assertTrue(cfgInsns.map { it.offset } == m.insns.map { it.offset })
        }
        assertTrue(methods.any { m -> DalvikCfgInput.toCfgInsns(m).any { it.kind != CfgInsnKind.NORMAL } })
    }
}
