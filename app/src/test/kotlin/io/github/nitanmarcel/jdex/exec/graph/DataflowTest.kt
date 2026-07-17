package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataflowTest {

    private fun analyze(shortId: String, args: List<Any?>?): DataflowResult {
        val src = Fixtures.sample()
        val m = src.method("LSample;", shortId)!!
        return Dataflow(Vm(src)).analyze(m, args)
    }

    @Test
    fun concretePropagatesThroughTransfer() {
        assertEquals(5, analyze("add(II)I", listOf(2, 3)).returnValue)
    }

    @Test
    fun unknownArgsYieldUnknownReturn() {
        assertTrue(analyze("add(II)I", null).returnValue is UnknownVal)
    }

    @Test
    fun loopTerminatesAtFixpoint() {
        val r = analyze("loop(I)I", listOf(5))
        assertTrue(r.returnValue is UnknownVal)
    }

    @Test
    fun concreteBranchPrunesUnreachableBlock() {
        val r = analyze("pick(Z)I", listOf(1))
        assertEquals(1, r.returnValue)
        assertTrue(r.reachable.any { !it })
    }

    @Test
    fun unknownBranchJoinsBothPaths() {
        val r = analyze("pick(Z)I", null)
        assertTrue(r.returnValue is UnknownVal)
        assertTrue(r.reachable.all { it })
    }
}
