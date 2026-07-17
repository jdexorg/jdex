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

class JdecUnreflectFieldTest {

    private fun body(withDispatch: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/reflect.dex")!!.readBytes()
        val dex = File.createTempFile("reflaccess", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val rd = ReflectiveDispatch(src)
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        d.addCustomPass(JdecValuePass { emptyList() })
        fun d(c: String) = "L${c.replace('.', '/')};"
        d.addCustomPass(JdecUnreflectPass({ _, _ -> null }, { _, _, _ -> null },
            if (withDispatch) { c, s, i -> rd.resolve(d(c), s, i) } else { _, _, _ -> null },
            if (withDispatch) { c, s, i, n -> rd.resolveInvoke(d(c), s, i, n) } else { _, _, _, _ -> null },
            if (withDispatch) { c, s -> rd.isReflectiveDispatcher(d(c), s) } else { _, _ -> false },
            afterTypes = true))
        d.load()
        return d.classesWithInners.first { it.rawName == "Caller" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun rewritesIndexedFieldGetterToDirectFieldAccess() {
        val base = body(false)
        val jdec = body(true)
        assertTrue(base.contains("Disp.n(")) { "baseline should still call the dispatcher:\n$base" }
        assertFalse(jdec.contains("Disp.n(")) { "field getter dispatcher must be removed:\n$jdec" }
        assertTrue(jdec.contains("bean.name")) { "must resolve to direct field access:\n$jdec" }
    }

    @Test
    fun rewritesIndexedMethodInvokeToDirectCall() {
        val base = body(false)
        val jdec = body(true)
        assertTrue(base.contains("Disp.m(")) { "baseline should still call the invoke dispatcher:\n$base" }
        assertFalse(jdec.contains("Disp.m(")) { "method-invoke dispatcher must be removed:\n$jdec" }
        assertTrue(jdec.contains(".greet(")) { "must resolve to a direct method call bean.greet(...):\n$jdec" }
    }
}
