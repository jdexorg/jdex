package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmTest {
    private val src = Fixtures.sample()
    private val vm = Vm(src)
    private fun m(id: String) = src.method("LSample;", id)!!

    @Test fun arithmetic() {
        assertEquals(5, vm.invoke(m("add(II)I"), listOf(2, 3)))
    }

    @Test fun loopWithBranches() {
        assertEquals(10, vm.invoke(m("loop(I)I"), listOf(5)))
        assertEquals(0, vm.invoke(m("loop(I)I"), listOf(0)))
    }

    @Test fun constStringReturn() {
        assertEquals("hi", vm.invoke(m("hello()Ljava/lang/String;")))
    }

    @Test fun inDexInvokeAndMoveResult() {
        assertEquals(6, vm.invoke(m("sum3(III)I"), listOf(1, 2, 3)))
    }

    @Test fun staticFieldThroughClinit() {
        assertEquals(42, vm.invoke(m("useConst()I")))
    }

    @Test fun arrayCreationAndStore() {
        val arr = vm.invoke(m("makeArr()[I"), emptyList(), DvmObject("LSample;"))
        assertTrue(arr is IntArray)
        arr as IntArray
        assertEquals(3, arr.size)
        assertEquals(7, arr[0])
        assertEquals(0, arr[1])
    }
}
