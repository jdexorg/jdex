package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class A1AnnotationPassesTest {

    private val src = Fixtures.a1demo()
    private val methods = src.allMethods().filter { it.declClass == "Lt/Demo;" }

    @Test fun returnValuePassAnnotatesConstantReturns() {
        val anns = AnalysisRunner(src, listOf(ReturnValuePass())).analyze(methods).filterIsInstance<InsnAnnotation>()
        val texts = anns.map { it.text }
        assertTrue(texts.any { it == "returns 6" }) { "factor() returns 6: $texts" }
        assertTrue(texts.any { it == "returns 49" }) { "square() returns 49: $texts" }
        assertTrue(texts.any { it == "returns \"big\"" }) { "classify returns \"big\": $texts" }
    }

    @Test fun decidedBranchConveyedByValueAndReachability() {
        val anns = AnalysisRunner(src, listOf(ValueResolutionPass(), ReachabilityPass()))
            .analyze(methods).filterIsInstance<InsnAnnotation>()
        val texts = anns.map { it.text }
        assertTrue(texts.any { it == "unreachable" }) { "classify's dead arm marked unreachable: $texts" }
    }
}
