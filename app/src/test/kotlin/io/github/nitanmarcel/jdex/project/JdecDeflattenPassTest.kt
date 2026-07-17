package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.DispatchExplorer
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecDeflattenPassTest {

    private fun computeBody(withPass: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/flattened.dex")!!.readBytes()
        val dex = File.createTempFile("flat", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        if (withPass) d.addCustomPass(JdecDeflattenPass { raw, sid ->
            src.method("L${raw.replace('.', '/')};", sid)?.let { DispatchExplorer(src).explore(it) }
        })
        d.load()
        val code = d.classesWithInners.first { it.rawName == "t.Flat" }.topParentClass.codeInfo.codeStr
        return code.substringAfter("int compute(").substringBefore("\n    }")
    }

    @Test
    fun rebuildsStructuredControlFlowFromFlattenedDispatch() {
        val base = computeBody(false)
        val jdec = computeBody(true)

        assertTrue(base.contains("hashCode()")) { "baseline should still be flattened:\n$base" }

        assertFalse(jdec.contains("hashCode()")) { "dispatcher must be removed:\n$jdec" }
        assertFalse(jdec.contains("Method not decompiled") || jdec.contains("JADX ERROR")) { "must decompile cleanly:\n$jdec" }
        assertTrue(jdec.contains("while") && jdec.contains("if ")) { "real loop+branch must be recovered:\n$jdec" }
        assertTrue(jdec.lines().size < base.lines().size / 2) { "deflattened code must be far smaller:\n$jdec" }
    }
}
