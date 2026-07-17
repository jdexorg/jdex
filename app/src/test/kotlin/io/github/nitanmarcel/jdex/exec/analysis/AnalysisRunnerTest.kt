package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalysisRunnerTest {

    private val src = Fixtures.sample()

    private fun resolveOn(shortId: String): List<InsnAnnotation> =
        AnalysisRunner(src, listOf(ValueResolutionPass())).analyze(listOf(src.method("LSample;", shortId)!!))

    @Test
    fun resolvesIntCallResult() {
        val anns = resolveOn("useAdd()I")
        assertTrue(anns.any { it is InsnAnnotation && it.text == "5" }) { "got: $anns" }
    }

    @Test
    fun resolvesStringThroughHostCall() {
        val anns = resolveOn("tag()Ljava/lang/String;")
        assertTrue(anns.any { it is InsnAnnotation && it.text == "\"hi x\"" }) { "got: $anns" }
    }

    @Test
    fun unknownArgCallsAreNotResolved() {
        val anns = resolveOn("sum3(III)I")
        assertTrue(anns.none { it is InsnAnnotation && it.text != "unreachable" && it.text.toIntOrNull() != null && it.text != "0" } ||
            anns.isEmpty()) { "unknown-arg calls should not produce a concrete annotation: $anns" }
    }

    @Test
    fun reachabilityMarksNothingForLiveMethod() {
        val anns = AnalysisRunner(src, listOf(ReachabilityPass())).analyze(listOf(src.method("LSample;", "loop(I)I")!!))
        assertTrue(anns.none { it.text == "unreachable" }) { "live method should have no dead code: $anns" }
    }

    @Test
    fun runnerSurvivesAllMethods() {
        val all = listOf("useAdd()I", "tag()Ljava/lang/String;", "sum3(III)I", "loop(I)I", "boxThruCall(I)I", "fact(I)I")
            .mapNotNull { src.method("LSample;", it) }
        val anns = AnalysisRunner(src, listOf(ValueResolutionPass(), ReachabilityPass())).analyze(all)
        assertTrue(anns.any { it is InsnAnnotation && it.text == "5" })
    }
}
