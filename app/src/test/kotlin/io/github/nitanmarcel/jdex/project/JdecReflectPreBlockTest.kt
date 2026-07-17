package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.ReflectiveDispatch
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecReflectPreBlockTest {

    private fun body(withRefl: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/reflect.dex")!!.readBytes()
        val dex = File.createTempFile("reflpreblock", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val rd = ReflectiveDispatch(src)
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        if (withRefl) {
            d.addCustomPass(JdecReflectPreBlock(
                fields = { raw, shortId ->
                    val m = src.method("L${raw.replace('.', '/')};", shortId)
                    if (m == null) emptyList() else rd.fieldSites(m)
                },
                invokes = { raw, shortId ->
                    val m = src.method("L${raw.replace('.', '/')};", shortId)
                    if (m == null) emptyList() else rd.invokeSites(m)
                },
            ))
        }
        d.load()
        return d.classesWithInners.first { it.rawName == "Caller" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun rewritesFieldGetterDispatcherPreBlock() {
        val base = body(false)
        val jdec = body(true)
        assertTrue(base.contains("Disp.n(")) { "baseline should still call the dispatcher:\n$base" }
        assertFalse(jdec.contains("Disp.n(")) { "field getter dispatcher must be gone:\n$jdec" }
        assertTrue(jdec.contains("bean.name")) { "must resolve to direct field access:\n$jdec" }
    }

    @Test
    fun rewritesMethodInvokeDispatcherPreBlock() {
        val base = body(false)
        val jdec = body(true)
        assertTrue(base.contains("Disp.m(")) { "baseline should still call the invoke dispatcher:\n$base" }
        assertFalse(jdec.contains("Disp.m(")) { "method-invoke dispatcher must be gone:\n$jdec" }
        assertTrue(jdec.contains(".greet(")) { "must resolve to a direct method call bean.greet(...):\n$jdec" }
    }
}
