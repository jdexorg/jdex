package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfgAnalysisTest {

    private val src = Fixtures.opaque()

    @Test
    fun decidesAlwaysTrueBranchAndMarksDeadCode() {
        val f = CfgAnalysis(src).analyze(src.method("LOpaque;", "alwaysTrue(I)I")!!)
        assertNotNull(f)
        assertTrue(f!!.decidedBranches.isNotEmpty()) { "expected a decided branch" }
        assertTrue(f.deadOffsets.isNotEmpty()) { "expected dead code" }
    }

    @Test
    fun decidesAlwaysFalseBranch() {
        val f = CfgAnalysis(src).analyze(src.method("LOpaque;", "alwaysFalse(I)I")!!)
        assertNotNull(f)
        assertTrue(f!!.decidedBranches.isNotEmpty()) { "expected a decided branch" }
        assertTrue(f.deadOffsets.isNotEmpty()) { "expected dead code" }
    }

    @Test
    fun leavesParamDependentBranchUndecided() {
        val f = CfgAnalysis(src).analyze(src.method("LOpaque;", "undecided(I)I")!!)
        assertNull(f) { "param-dependent branch must not resolve: $f" }
    }
}
