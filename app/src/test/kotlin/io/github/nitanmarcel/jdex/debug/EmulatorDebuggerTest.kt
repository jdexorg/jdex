package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmulatorDebuggerTest {

    private fun dbg() = EmulatorDebugger(Fixtures.sample())

    @Test
    fun runInspectResumeReturn() {
        val d = dbg()
        d.runMethod("LSample;->boxGet(I)I", listOf(7), pauseAtEntry = true)
        assertTrue(d.awaitStop())
        assertTrue(d.state is DebugState.Stopped)
        assertTrue(d.frames().isNotEmpty())
        assertTrue(d.variables(0).isNotEmpty())
        d.resume()
        assertTrue(d.awaitFinished())
        assertEquals(7, d.returnValue())
    }

    @Test
    fun setValueReplacesRegister() {
        val d = dbg()
        d.runMethod("LSample;->loop(I)I", listOf(5), pauseAtEntry = true)
        assertTrue(d.awaitStop())
        val nVar = d.variables(0).first { it.value == "5" && it.editKey != null }
        assertTrue(d.setValue(nVar.editKey!!, "3"))
        d.resume()
        assertTrue(d.awaitFinished())
        assertEquals(3, d.returnValue())
    }

    @Test
    fun resolveExposesAbstractValues() {
        val d = dbg()
        assertEquals(6, d.resolve("LSample;->sum3(III)I", listOf(1, 2, 3)).returnValue)
        assertTrue(d.resolve("LSample;->pick(Z)I", null).returnValue is UnknownVal)
    }

    @Test
    fun registerStubIsAccepted() {
        val d = dbg()
        d.registerStub("Landroid/text/TextUtils;", "isEmpty") { _, a -> (a.getOrNull(0) as? CharSequence).isNullOrEmpty() }
        d.registerField("Landroid/os/Build;", "MODEL", "test-device")
    }
}
