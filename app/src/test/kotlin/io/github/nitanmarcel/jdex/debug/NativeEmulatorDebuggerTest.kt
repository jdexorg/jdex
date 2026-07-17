package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.KeystoneAssembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeEmulatorDebuggerTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = ByteArray(s.length) { (s[it].code xor key[it % 3].toInt()).toByte() }
    private fun so() = NativeEmulatorDebuggerTest::class.java.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()

    @Test
    fun runStopInspectStepResume() {
        NativeEmulatorDebugger(so(), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            val states = ArrayList<DebugState>()
            dbg.onStateChange { states.add(it) }
            dbg.runMethod("com/jdex/crypto/Native", "decryptBytes([B)Ljava/lang/String;", listOf(enc("hello")))

            assertTrue(dbg.awaitStop(), "pauses at entry")
            val stopped = dbg.state
            assertTrue(stopped is DebugState.Stopped && stopped.location is DebugLocation.Native)
            assertTrue(dbg.frames().isNotEmpty(), "a native frame at the stop")
            val regs = dbg.variables(0).associate { it.name to it.value }
            assertTrue("pc" in regs && "x2" in regs, "registers exposed as variables")

            val pc0 = (dbg.frames()[0].location as DebugLocation.Native).pc
            dbg.stepOver()
            assertTrue(dbg.awaitStop())
            assertNotEquals(pc0, (dbg.frames()[0].location as DebugLocation.Native).pc, "step advanced")

            dbg.resume()
            assertTrue(dbg.awaitFinished())
            assertEquals("hello", dbg.returnValue())
            assertEquals(DebugState.Detached, dbg.state)
        }
    }

    @Test
    fun supportsRegisterMemoryAndInstructionPatching() {
        NativeEmulatorDebugger(so(), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            val addr = dbg.symbolAddress("decryptBytes") ?: error("symbol missing")
            dbg.addBreakpoint(Breakpoint.Native("demo:libjnicrypt.so", addr - dbg.moduleBase))
            dbg.runMethod("com/jdex/crypto/Native", "decryptBytes([B)Ljava/lang/String;", listOf(enc("hi")), pauseAtEntry = false)
            assertTrue(dbg.awaitStop())

            assertTrue(dbg.setValue("r:x0", "0x1234"))
            assertEquals("0x1234", dbg.variables(0).first { it.name == "x0" }.value)
            assertTrue(dbg.setRegister("x5", 0xCAFEL))

            assertTrue(dbg.writeMemory(addr, byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())))
            assertTrue(!KeystoneAssembler.available() || dbg.patchNative(addr - dbg.moduleBase, "nop"))

            dbg.detach()
        }
    }

    @Test
    fun nativeBreakpointByFileOffset() {
        NativeEmulatorDebugger(so(), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            val addr = dbg.symbolAddress("decryptBytes") ?: error("symbol missing")
            dbg.addBreakpoint(Breakpoint.Native("demo:libjnicrypt.so", addr - dbg.moduleBase))
            dbg.runMethod("com/jdex/crypto/Native", "decryptBytes([B)Ljava/lang/String;", listOf(enc("hi")), pauseAtEntry = false)
            assertTrue(dbg.awaitStop(), "stops at the native breakpoint")
            dbg.resume()
            assertTrue(dbg.awaitFinished())
            assertEquals("hi", dbg.returnValue())
        }
    }
}
