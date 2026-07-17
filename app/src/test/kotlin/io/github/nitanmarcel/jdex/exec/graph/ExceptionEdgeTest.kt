package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import jadx.api.plugins.input.insns.Opcode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExceptionEdgeTest {

    @Test
    fun catchReachableViaExplicitThrowIsNotDead() {
        val src = Fixtures.tryCatch()
        val m = src.method("Lt/Tc;", "f(I)I")!!
        val r = Dataflow(Vm(src)).analyze(m)
        assertTrue(r.complete) { "analysis must complete" }
        val moveExc = m.insns.first { it.opcode == Opcode.MOVE_EXCEPTION }
        assertTrue(r.isReachableOffset(moveExc.offset)) { "the catch (move-exception @${moveExc.offset}) must be reachable" }
    }
}
