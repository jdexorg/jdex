package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BooleanRenderTest {

    private val src = Fixtures.sample()

    private fun texts(pass: AnalysisPass, vararg ids: String): List<String> =
        AnalysisRunner(src, listOf(pass)).analyze(ids.map { src.method("LSummaryReuse;", it)!! })
            .filterIsInstance<InsnAnnotation>().map { it.text }

    @Test
    fun booleanReturnsRenderAsTrueFalse() {
        val t = texts(ReturnValuePass(), "yes()Z", "cmp()Z", "twice()I")
        assertTrue(t.contains("returns true")) { t.toString() }
        assertTrue(t.contains("returns 20")) { "int returns must stay numeric: $t" }
        assertTrue(t.none { it == "returns 1" || it == "returns 0" }) { "no raw bool ints: $t" }
    }

    @Test
    fun booleanCallResultsRenderAsTrueFalse() {
        val t = texts(ValueResolutionPass(), "useYes()Z")
        assertTrue(t.contains("true")) { t.toString() }
        assertTrue(t.none { it == "1" || it == "0" }) { "no raw bool ints: $t" }
    }
}
