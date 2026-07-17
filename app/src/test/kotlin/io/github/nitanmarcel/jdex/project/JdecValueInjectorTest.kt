package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.AnalysisRunner
import io.github.nitanmarcel.jdex.exec.analysis.StringDecryptor
import io.github.nitanmarcel.jdex.exec.analysis.ValueResolutionPass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecValueInjectorTest {

    private fun sessionAndAnnotations(): Pair<ApkSession, List<io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation>> {
        val src = Fixtures.sample()
        val methods = listOf("useAdd()I", "tag()Ljava/lang/String;", "secret()Ljava/lang/String;")
            .mapNotNull { src.method("LSample;", it) }
        val anns = AnalysisRunner(src, listOf(ValueResolutionPass())).analyze(methods) +
            StringDecryptor(src).recoverAll(methods)

        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/sample.dex")!!.readBytes()
        val dex = File.createTempFile("jdex-sample", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        return ApkSession.load(dex, "jdectest") to anns
    }

    @Test
    fun injectsResolvedValuesAtTheRightLines() {
        val (session, anns) = sessionAndAnnotations()
        val cls = session.decompiler().classes.first { it.classNode.classInfo.fullName == "Sample" || it.fullName == "Sample" }
        val java = JdecValueInjector.annotatedSource(cls, cls.topParentClass.codeInfo, anns)

        assertTrue(java.contains("// jdex:")) { "expected injected annotations" }
        assertTrue(java.lines().any { it.contains("add(") && it.contains("// jdex: 5") }) { java }
        assertTrue(java.lines().any { it.contains("// jdex:") && it.contains("\"hi\"") }) { java }
    }
}
