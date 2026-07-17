package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EmulatorRunToCursorTest {

    private fun src(): DexInputSource {
        val bytes = EmulatorRunToCursorTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        return DexInputSource.load(dex)
    }

    private fun invokeOffset(s: DexInputSource): Int =
        s.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
            .insns.first { (it.ref as? MethodRef)?.name == "decryptBytes" }.offset

    @Test
    fun runToStartsAndStopsAtTheGivenPc() {
        val s = src()
        val off = invokeOffset(s)
        val dbg = EmulatorDebugger(s)
        dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", runTo = off)
        val stopped = (0 until 100).firstOrNull { dbg.state is DebugState.Stopped || Thread.sleep(30).let { false } } != null
        assertTrue(stopped, "run-to stops")
        assertEquals(off, ((dbg.state as DebugState.Stopped).location as DebugLocation.Dex).dexPc)
    }

    @Test
    fun mixedRunToCursorContinuesToAPc() {
        val s = src()
        val soBytes = EmulatorRunToCursorTest::class.java.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()
        val off = invokeOffset(s)
        MixedEmulatorDebugger(s, soBytes, is64 = true, nativeId = "lib").use { dbg ->
            dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", runTo = off)
            val stopped = (0 until 100).firstOrNull { dbg.state is DebugState.Stopped || Thread.sleep(30).let { false } } != null
            assertTrue(stopped)
            assertEquals(off, ((dbg.state as DebugState.Stopped).location as DebugLocation.Dex).dexPc)
        }
    }
}
