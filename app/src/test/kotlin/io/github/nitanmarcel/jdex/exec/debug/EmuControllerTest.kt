package io.github.nitanmarcel.jdex.exec.debug

import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmuControllerTest {

    private fun setup(shortId: String): Pair<EmuController, Pair<Vm, DexMethod>> {
        val src = Fixtures.sample()
        val ctl = EmuController()
        val vm = Vm(src, limits = ExecLimits(maxMillis = 0), hook = ctl)
        return ctl to (vm to src.method("LSample;", shortId)!!)
    }

    @Test
    fun breakpointAtEntryStopsThenResumes() {
        val (ctl, vmM) = setup("loop(I)I")
        val (vm, m) = vmM
        ctl.addBreakpoint("LSample;->loop(I)I", 0)
        ctl.start(vm, m, listOf(5), pauseAtEntry = false)
        assertTrue(ctl.awaitStop())
        assertEquals(EmuState.STOPPED, ctl.state)
        assertEquals(0, ctl.top()!!.pc)
        ctl.resume()
        assertTrue(ctl.awaitFinished())
        assertEquals(10, ctl.returnValue)
    }

    @Test
    fun pauseAtEntryStepThenResume() {
        val (ctl, vmM) = setup("loop(I)I")
        val (vm, m) = vmM
        ctl.start(vm, m, listOf(5), pauseAtEntry = true)
        assertTrue(ctl.awaitStop())
        assertEquals("LSample;->loop(I)I", ctl.top()!!.descriptor)
        val firstPc = ctl.top()!!.pc
        ctl.stepInto()
        assertTrue(ctl.awaitStop())
        assertTrue(ctl.top()!!.pc != firstPc)
        ctl.resume()
        assertTrue(ctl.awaitFinished())
        assertEquals(10, ctl.returnValue)
    }

    @Test
    fun replacingParamRegisterChangesResult() {
        val (ctl, vmM) = setup("loop(I)I")
        val (vm, m) = vmM
        ctl.start(vm, m, listOf(5), pauseAtEntry = true)
        assertTrue(ctl.awaitStop())
        ctl.top()!!.frame.set(m.registersCount - 1, 3)
        ctl.resume()
        assertTrue(ctl.awaitFinished())
        assertEquals(3, ctl.returnValue)
    }

    @Test
    fun stepOverDoesNotDescendIntoCallees() {
        val (ctl, vmM) = setup("sum3(III)I")
        val (vm, m) = vmM
        ctl.start(vm, m, listOf(1, 2, 3), pauseAtEntry = true)
        assertTrue(ctl.awaitStop())
        var guard = 0
        while (ctl.state == EmuState.STOPPED && guard++ < 200) {
            assertEquals(1, ctl.frames().size)
            ctl.stepOver()
            ctl.awaitStop()
        }
        assertEquals(EmuState.FINISHED, ctl.state)
        assertEquals(6, ctl.returnValue)
    }
}
