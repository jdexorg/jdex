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

class JdecDeflattenCatchTest {

    private fun body(withPass: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/dispatch.dex")!!.readBytes()
        val dex = File.createTempFile("dispatchcf", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        if (withPass) d.addCustomPass(JdecDeflattenPass { raw, sid ->
            src.method("L${raw.replace('.', '/')};", sid)?.let { DispatchExplorer(src).explore(it) }
        })
        d.load()
        return d.classesWithInners.first { it.rawName == "CatchFlat" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun catchBlockFlatteningIsRebuiltWithoutDroppingTheHandler() {
        val base = body(false)
        val jdec = body(true)

        assertTrue(base.contains("while (true)")) { "baseline catch must still be flattened:\n$base" }

        assertFalse(jdec.contains("while (true)")) { "the catch-block dispatcher must be removed:\n$jdec" }
        assertTrue(jdec.contains("\"CAUGHT\"")) { "the handler's real result must survive (not pruned):\n$jdec" }
        assertTrue(jdec.contains("\"NORMAL\"")) { "the normal-path result must be preserved:\n$jdec" }
        assertFalse(jdec.contains("JADX ERROR") || jdec.contains("Method not decompiled")) { "must decompile cleanly:\n$jdec" }
    }
}
