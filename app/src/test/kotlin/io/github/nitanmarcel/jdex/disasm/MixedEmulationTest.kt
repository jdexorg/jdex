package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.debug.EmuController
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MixedEmulationTest {

    private fun res(name: String) = MixedEmulationTest::class.java.getResourceAsStream(name)!!.readBytes()

    @Test
    fun dexRunStopsAtNativeBreakpointThenResumes() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)

        MixedNativeBridge(src, res("/jni/libjnicrypt_arm64-v8a.so"), is64 = true).use { bridge ->
            val nativeCtrl = bridge.controller
            val dexCtrl = EmuController()
            val vm = Vm(src, hook = dexCtrl, nativeBridge = bridge)

            val addr = nativeCtrl.symbolAddress("decryptBytes") ?: error("decryptBytes not found")
            nativeCtrl.addBreakpoint(addr)

            val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
            dexCtrl.start(vm, run, pauseAtEntry = false)

            assertTrue(nativeCtrl.awaitStopped(), "dex run suspends at the native breakpoint")
            assertEquals(NativeEmuState.STOPPED, nativeCtrl.state)
            assertEquals(addr, nativeCtrl.pc())
            assertTrue(dexCtrl.frames().any { it.method.ref.name == "run" }, "dex frame beneath the native stop")

            nativeCtrl.resume()
            assertTrue(dexCtrl.awaitFinished(), "dex run completes after the native resume")
            assertEquals("hello", dexCtrl.returnValue)
        }
    }
}
