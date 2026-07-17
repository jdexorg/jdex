package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.CapstoneDisassembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NativeEmuHarnessTest {

    private fun libBytes() = javaClass.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()

    @Test
    fun `malloc mem and register round-trip`() {
        assumeTrue(CapstoneDisassembler.available())
        NativeEmulatorDebugger(libBytes(), is64 = true, nativeId = "lib").use { dbg ->
            val p = dbg.malloc(16)
            assertNotEquals(0L, p)
            assertTrue(dbg.writeMemory(p, byteArrayOf(1, 2, 3, 4)))
            assertEquals(listOf<Byte>(1, 2, 3, 4), dbg.readMemory(p, 4)!!.toList())

            assertTrue(dbg.setRegister("x5", 0x1234L))
            assertEquals(0x1234L, dbg.regRead("x5"))
        }
    }

    private fun sumSetup(dbg: NativeEmulatorDebugger): Pair<Long, Long> {
        val sym = dbg.symbolAddress("jdex_sum")!!
        val p = dbg.malloc(8)
        dbg.writeMemory(p, byteArrayOf(10, 20, 30, 40))
        return sym to p
    }

    @Test
    fun `call runs a plain function by address and returns the result`() {
        assumeTrue(CapstoneDisassembler.available())
        NativeEmulatorDebugger(libBytes(), is64 = true, nativeId = "lib").use { dbg ->
            val (sym, p) = sumSetup(dbg)
            assertEquals(100L, dbg.callAddress(sym, listOf(p, 4)))
        }
    }

    @Test
    fun `emulate runs a range with a register state the caller sets`() {
        assumeTrue(CapstoneDisassembler.available())
        NativeEmulatorDebugger(libBytes(), is64 = true, nativeId = "lib").use { dbg ->
            val (sym, p) = sumSetup(dbg)
            dbg.setRegister("x0", p)
            dbg.setRegister("x1", 4)
            dbg.setRegister("lr", dbg.moduleBase)
            assertEquals(100L, dbg.emulate(sym, dbg.moduleBase))
        }
    }

    @Test
    fun `call blocks then pauses at a breakpoint, resumes to finish`() {
        assumeTrue(CapstoneDisassembler.available())
        NativeEmulatorDebugger(libBytes(), is64 = true, nativeId = "lib").use { dbg ->
            val (sym, p) = sumSetup(dbg)
            dbg.addBreakpoint(Breakpoint.Native("lib", sym - dbg.moduleBase))
            assertEquals(null, dbg.callAddress(sym, listOf(p, 4)))
            assertTrue(dbg.state is DebugState.Stopped)
            dbg.resume()
            assertTrue(dbg.awaitFinished(5000))
            assertEquals(100L, dbg.returnValue())
        }
    }
}
