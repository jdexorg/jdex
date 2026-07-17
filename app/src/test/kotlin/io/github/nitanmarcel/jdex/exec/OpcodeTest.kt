package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpcodeTest {

    private val src = Fixtures.ops()

    @Test
    fun filledNewArrayOfInts() {
        val m = src.method("LOps;", "arr(I)I")!!
        assertEquals(18, Dataflow(Vm(src)).analyze(m, listOf(5)).returnValue)
    }

    @Test
    fun filledNewArrayOfObjects() {
        val m = src.method("LOps;", "strs()I")!!
        assertEquals(5, Dataflow(Vm(src)).analyze(m, null).returnValue)
    }

    @Test
    fun floatConstIsReinterpretedNotNumeric() {
        val src = Fixtures.flt()
        fun ret(id: String, args: List<Any?>?) = Dataflow(Vm(src)).analyze(src.method("LFlt;", id)!!, args).returnValue
        assertEquals(2.0f, ret("two()F", null))
        assertEquals(2.5, ret("twoD()D", null))
        assertEquals(6.0f, ret("mul(F)F", listOf(2.0f)))
        assertEquals(2.5, ret("add(D)D", listOf(1.0)))
    }
}
