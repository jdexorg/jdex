package io.github.nitanmarcel.jdex.exec

import jadx.api.plugins.input.insns.Opcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlatteningTest {

    private val src = Fixtures.flattened()
    private val m = src.method("Lt/Flat;", "compute(I)I")!!

    @Test
    fun engineExecutesControlFlowFlattenedCodeCorrectly() {
        assertEquals(48, Vm(src).invoke(m, listOf(5)))
        assertEquals(-16, Vm(src).invoke(m, listOf(-1)))
    }

    @Test
    fun realSparseSwitchDispatchersHaveLinkedPayloads() {
        val switches = m.insns.filter { it.opcode == Opcode.SPARSE_SWITCH }
        assertTrue(switches.isNotEmpty()) { "expected a dispatcher" }
        assertTrue(switches.all { it.payload != null }) { "every dispatcher switch must have its payload linked" }
    }
}
