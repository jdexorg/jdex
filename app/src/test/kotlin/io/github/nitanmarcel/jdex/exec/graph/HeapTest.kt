package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeapTest {

    private fun analyze(shortId: String, args: List<Any?>?): DataflowResult {
        val src = Fixtures.sample()
        val m = src.method("LSample;", shortId)!!
        return Dataflow(Vm(src)).analyze(m, args)
    }

    @Test
    fun fieldRoundTripIsPrecise() {
        assertEquals(7, analyze("boxGet(I)I", listOf(7)).returnValue)
    }

    @Test
    fun crossPathFieldWriteIsSoundlyUnknown() {
        assertTrue(analyze("boxPick(Z)I", null).returnValue is UnknownVal)
    }

    @Test
    fun loopFieldTerminatesAtFixpoint() {
        assertTrue(analyze("boxLoop(I)I", listOf(5)).returnValue is UnknownVal)
    }

    @Test
    fun interproceduralFieldWriteIsVisible() {
        assertEquals(9, analyze("boxThruCall(I)I", listOf(9)).returnValue)
    }
}
