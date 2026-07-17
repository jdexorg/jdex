package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MixedEmulatorDebuggerTest {

    private fun res(name: String) = MixedEmulatorDebuggerTest::class.java.getResourceAsStream(name)!!.readBytes()

    @Test
    fun dexRunSuspendsAtNativeBreakpointWithSplicedStack() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)

        MixedEmulatorDebugger(src, res("/jni/libjnicrypt_arm64-v8a.so"), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            val addr = dbg.nativeSymbolAddress("decryptBytes") ?: error("symbol missing")
            dbg.addBreakpoint(Breakpoint.Native("demo:libjnicrypt.so", addr - dbg.nativeModuleBase))
            dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", pauseAtEntry = false)

            val stopped = (0 until 100).firstOrNull { dbg.state is DebugState.Stopped || Thread.sleep(50).let { false } } != null
            assertTrue(stopped, "the mixed run suspends at the native breakpoint")
            val loc = (dbg.state as DebugState.Stopped).location
            assertTrue(loc is DebugLocation.Native && loc.pc == addr, "stopped in native at decryptBytes")

            val frames = dbg.frames()
            assertTrue(frames.first().location is DebugLocation.Native, "native frame on top")
            assertTrue(frames.any { (it.location as? DebugLocation.Dex)?.methodDescriptor?.contains("App;->run") == true }, "dex frame beneath")
            assertTrue(dbg.variables(0).any { it.name == "x2" }, "native registers at the native frame")

            dbg.resume()
            assertTrue(dbg.awaitFinished())
            assertEquals("hello", dbg.returnValue())
            assertEquals(DebugState.Detached, dbg.state)
        }
    }

    @Test
    fun stepIntoAtDexInvokeDescendsIntoNative() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)

        MixedEmulatorDebugger(src, res("/jni/libjnicrypt_arm64-v8a.so"), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            val run = "Lcom/jdex/crypto/App;->run()Ljava/lang/String;"
            val invoke = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
                .insns.first { (it.ref as? MethodRef)?.name == "decryptBytes" }
            dbg.addBreakpoint(Breakpoint.Dex(run, invoke.offset))
            dbg.runMethod(run, pauseAtEntry = false)

            val atInvoke = (0 until 100).firstOrNull { (dbg.state as? DebugState.Stopped)?.location is DebugLocation.Dex || Thread.sleep(50).let { false } } != null
            assertTrue(atInvoke, "stopped at the dex invoke of the native method")

            dbg.stepInto()
            val inNative = (0 until 100).firstOrNull { (dbg.state as? DebugState.Stopped)?.location is DebugLocation.Native || Thread.sleep(50).let { false } } != null
            assertTrue(inNative, "step-into descends into the native callee")
            assertTrue(dbg.frames().first().location is DebugLocation.Native, "native frame on top after step-into")
        }
    }

    @Test
    fun editsDexRegisterFromTheVariablePanel() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)

        MixedEmulatorDebugger(src, res("/jni/libjnicrypt_arm64-v8a.so"), is64 = true, nativeId = "demo:libjnicrypt.so").use { dbg ->
            dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", pauseAtEntry = true)
            val stopped = (0 until 100).firstOrNull { dbg.state is DebugState.Stopped || Thread.sleep(50).let { false } } != null
            assertTrue(stopped, "dex run pauses at entry")

            assertTrue(dbg.setValue("d:0:0:I", "42"), "edit dex register v0")
            val v0 = dbg.variables(0).first()
            assertEquals("42", v0.value)
            assertEquals("d:0:0:I", v0.editKey)
        }
    }
}
