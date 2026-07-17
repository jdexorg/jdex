package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SummaryTest {

    private fun analyze(shortId: String, args: List<Any?>?): DataflowResult {
        val src = Fixtures.sample()
        val m = src.method("LSample;", shortId)!!
        return Dataflow(Vm(src)).analyze(m, args)
    }

    @Test
    fun resolvesThroughNestedInDexCalls() {
        assertEquals(6, analyze("sum3(III)I", listOf(1, 2, 3)).returnValue)
    }

    @Test
    fun unknownArgsPropagateThroughCalls() {
        assertTrue(analyze("sum3(III)I", null).returnValue is UnknownVal)
    }

    @Test
    fun resolvesThroughDeterministicHostCall() {
        assertEquals("hi x", analyze("greet(Ljava/lang/String;)Ljava/lang/String;", listOf("x")).returnValue)
    }

    @Test
    fun recursionTerminatesConservatively() {
        assertTrue(analyze("fact(I)I", listOf(5)).returnValue is UnknownVal)
        assertTrue(analyze("fact(I)I", null).returnValue is UnknownVal)
    }
}
