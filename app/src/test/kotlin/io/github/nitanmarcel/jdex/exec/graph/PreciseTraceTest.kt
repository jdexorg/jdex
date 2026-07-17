package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.analysis.CfgAnalysis
import jadx.api.plugins.input.insns.Opcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreciseTraceTest {

    private val src = Fixtures.opaque()

    @Test
    fun unrollsLoopAndDecidesPostLoopOpaqueGuard() {
        val m = src.method("LTrace;", "trace()I")!!
        val facts = PreciseTrace(Vm(src)).trace(m)
        assertTrue(facts.complete) { "expected complete exploration" }
        val ifNe = m.insns.first { it.opcode == Opcode.IF_NE }.offset
        assertEquals(1, facts.takenTargets[ifNe]?.size) {
            "post-loop opaque guard should take exactly one edge: ${facts.takenTargets[ifNe]}"
        }
    }

    @Test
    fun paramDependentLoopDoesNotComplete() {
        val m = src.method("LTrace;", "traceInput(I)I")!!
        val facts = PreciseTrace(Vm(src)).trace(m)
        assertFalse(facts.complete) { "input-dependent loop must not complete" }
    }

    @Test
    fun cfgAnalysisFoldsLoopCarriedOpaqueGuardTheFixpointCannot() {
        val m = src.method("LTrace;", "trace()I")!!
        val ifNe = m.insns.first { it.opcode == Opcode.IF_NE }.offset
        val f = CfgAnalysis(src).analyze(m)
        assertNotNull(f)
        assertTrue(ifNe in f!!.decidedBranches) {
            "precise trace should fold the post-loop opaque guard the fixpoint widens away: ${f.decidedBranches}"
        }
    }

    @Test
    fun cfgAnalysisLeavesInputDependentGuardUndecided() {
        val m = src.method("LTrace;", "traceInput(I)I")!!
        val ifNe = m.insns.first { it.opcode == Opcode.IF_NE }.offset
        val f = CfgAnalysis(src).analyze(m)
        assertTrue(f == null || ifNe !in f.decidedBranches) {
            "input-dependent guard must stay undecided: ${f?.decidedBranches}"
        }
    }
}
