package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.debug.Breakpoint
import io.github.nitanmarcel.jdex.debug.DebugLocation
import io.github.nitanmarcel.jdex.debug.DebugState
import io.github.nitanmarcel.jdex.debug.DebugVar
import io.github.nitanmarcel.jdex.debug.DebuggerBase
import io.github.nitanmarcel.jdex.debug.Frame
import io.github.nitanmarcel.jdex.debug.HookableEmulator
import io.github.nitanmarcel.jdex.debug.ThreadInfo
import io.github.nitanmarcel.jdex.exec.Interceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveEmuControlTest {

    private class FakeEmu : DebuggerBase, HookableEmulator {
        var resumed = false
        val hooked = ArrayList<String>()
        override val state get() = DebugState.Stopped(DebugLocation.Dex("Lx;->m()V", 4))
        override fun resume() { resumed = true }
        override fun pause() {}
        override fun stepInto() {}
        override fun stepOver() {}
        override fun stepOut() {}
        override fun detach() {}
        override fun addBreakpoint(bp: Breakpoint) {}
        override fun removeBreakpoint(bp: Breakpoint) {}
        override fun threads() = listOf(ThreadInfo(1, "emu", "stopped", true))
        override fun currentThreadId() = 1L
        override fun frames(threadId: Long) = listOf(Frame(0, "Lx;->m()V @4", DebugLocation.Dex("Lx;->m()V", 4)))
        override fun variables(threadId: Long, frameIndex: Int) = emptyList<DebugVar>()
        override fun children(ref: Long) = emptyList<DebugVar>()
        override fun onStateChange(listener: (DebugState) -> Unit) {}
        override fun addHook(descriptor: String, hook: Interceptor): Int { hooked.add(descriptor); return 7 }
        override fun removeHook(id: Int): Boolean = true
    }

    @Test
    fun delegatesObserveAndDriveThrowsOnRun() {
        val fake = FakeEmu()
        val c = ActiveEmuControl(activeEmu = { fake }, world = { io.github.nitanmarcel.jdex.exec.EmuWorld() }, source = { null })
        assertEquals("stopped", c.state())
        assertEquals(1, c.frames().size)
        c.resume(); assertTrue(fake.resumed)
        assertThrows(UnsupportedOperationException::class.java) { c.run("Lx;->m()V", emptyList()) }
        assertThrows(UnsupportedOperationException::class.java) { c.awaitFinished(10) }
    }

    @Test
    fun inactiveWhenNoSession() {
        val c = ActiveEmuControl(activeEmu = { null }, world = { io.github.nitanmarcel.jdex.exec.EmuWorld() }, source = { null })
        assertEquals("detached", c.state())
    }

    @Test
    fun hooksWorkIdleViaSessionRegistry() {
        val reg = io.github.nitanmarcel.jdex.exec.HookRegistry()
        val c = ActiveEmuControl(activeEmu = { null }, world = { io.github.nitanmarcel.jdex.exec.EmuWorld(hooks = reg) }, source = { null })
        val id = c.hook("Lx;->m()V") { }
        assertTrue(id > 0)
        assertEquals(listOf(mapOf<String, Any?>("id" to id, "descriptor" to "Lx;->m()V")), c.installedHooks())
        assertTrue(c.unhook(id))
        c.hook("Ly;->n()V") { }
        c.clearHooks()
        assertEquals(emptyList<Map<String, Any?>>(), c.installedHooks())
    }
}
