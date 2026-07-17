package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.EngineContext
import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutableStaticTest {

    private val src = Fixtures.mutStatic()
    private fun facts(id: String) =
        CfgAnalysis(src, ctx = EngineContext(src)).analyze(src.method("LMutStatic;", id)!!)

    private fun noBranchDecided(id: String) =
        facts(id)?.decidedBranches?.isEmpty() ?: true

    @Test
    fun mutableStaticFieldBranchNotDecided() {
        assertTrue(noBranchDecided("useH()I"))
    }

    @Test
    fun clinitOnlyStaticFieldStillDecides() {
        assertTrue(facts("useK()I")?.decidedBranches?.isNotEmpty() == true) { "clinit-only static must resolve" }
    }

    @Test
    fun mutableInstanceFieldBranchNotDecided() {
        assertTrue(noBranchDecided("useGc()I"))
    }

    @Test
    fun ctorOnlyInstanceFieldStillDecides() {
        assertTrue(facts("useGd()I")?.decidedBranches?.isNotEmpty() == true) { "ctor-only field must resolve" }
    }

    @Test
    fun mutableContainerStaticBranchNotDecided() {
        assertTrue(noBranchDecided("useL()I"))
    }
}
