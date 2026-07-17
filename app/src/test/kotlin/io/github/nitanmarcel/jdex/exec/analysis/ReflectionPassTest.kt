package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReflectionPassTest {

    private val src = Fixtures.reflect()

    private fun annotate(shortId: String): List<String> {
        val m = src.method("LRefl;", shortId)!!
        return AnalysisRunner(src, listOf(ReflectionPass())).analyze(listOf(m))
            .filterIsInstance<InsnAnnotation>().map { it.text }
    }

    @Test
    fun resolvesReflectionTargetsToAppMethod() {
        val texts = annotate("viaApp(Ljava/lang/String;)Ljava/lang/Object;")
        assertTrue(texts.any { it == "→ Refl" }, "forName resolves the class: $texts")
        assertTrue(texts.any { it == "→ Refl.secret(String)" }, "getDeclaredMethod resolves the target: $texts")
        assertTrue(texts.any { it == "invokes Refl.secret(String)" }, "invoke names the reflected target: $texts")
    }

    @Test
    fun resolvesFrameworkTargetsViaSymbolicHandle() {
        val texts = annotate("viaFramework(Ljava/lang/String;)Ljava/lang/Object;")
        assertTrue(texts.any { it == "→ android.os.SystemProperties" }, "forName on a framework class: $texts")
        assertTrue(texts.any { it == "invokes android.os.SystemProperties.get(String)" }, "framework target named via symbolic handle: $texts")
    }
}
