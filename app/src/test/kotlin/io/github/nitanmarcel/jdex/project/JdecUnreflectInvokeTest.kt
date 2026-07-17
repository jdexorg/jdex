package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeTarget
import io.github.nitanmarcel.jdex.exec.analysis.ReflectionPass
import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecUnreflectInvokeTest {

    @Test
    fun rewritesReflectiveInvokeToDirectCallForAppTarget() {
        val src = Fixtures.reflect()
        val refl = ReflectionPass()
        val dex = File.createTempFile("jdex-reflinv", ".dex").apply {
            deleteOnExit()
            writeBytes(JdecUnreflectInvokeTest::class.java.getResourceAsStream("/fixtures/reflect.dex")!!.readBytes())
        }
        fun resolve(rawName: String, shortId: String, offset: Int): ReflectInvokeTarget? {
            val m = src.method("L${rawName.replace('.', '/')};", shortId) ?: return null
            val result = runCatching { Dataflow(Vm(src)).analyze(m) }.getOrNull()?.takeIf { it.complete } ?: return null
            return refl.invokeTargets(m, result)[offset]
        }
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        d.addCustomPass(JdecValuePass { emptyList() })
        d.addCustomPass(JdecUnreflectPass({ _, _ -> null }, ::resolve))
        d.load()
        val code = d.classesWithInners.first { it.rawName == "Refl" }.topParentClass.codeInfo.codeStr
        val viaApp = code.substringAfter("Object viaApp").substringBefore("Object viaFramework")

        assertTrue(viaApp.contains("secret(")) { "the reflective invoke must become a direct call to secret():\n$viaApp" }
        assertFalse(Regex("\\binvoke\\(").containsMatchIn(viaApp)) { "the Method.invoke must be gone:\n$viaApp" }

        val viaFw = code.substringAfter("Object viaFramework")
        assertTrue(Regex("\\binvoke\\(").containsMatchIn(viaFw)) { "framework (symbolic) targets must NOT be rewritten:\n$viaFw" }
    }
}
