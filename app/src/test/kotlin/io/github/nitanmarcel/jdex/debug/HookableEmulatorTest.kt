package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HookableEmulatorTest {

    @Test
    fun addedHookReplacesReturnDuringRun() {
        val bytes = HookableEmulatorTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val dbg = EmulatorDebugger(src)
        dbg.addHook("Lcom/jdex/crypto/Native;->decryptBytes([B)Ljava/lang/String;") { it.replace("HOOKED") }
        dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", pauseAtEntry = false)
        assertTrue(dbg.awaitFinished(5000))
        assertEquals("HOOKED", dbg.returnValue())
    }

    @Test
    fun sharedRegistryAppliesToEveryDebugger() {
        val bytes = HookableEmulatorTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val shared = io.github.nitanmarcel.jdex.exec.HookRegistry()
        shared.add("Lcom/jdex/crypto/Native;->decryptBytes([B)Ljava/lang/String;") { it.replace("SHARED") }
        for (i in 0..1) {
            val dbg = EmulatorDebugger(src, world = io.github.nitanmarcel.jdex.exec.EmuWorld(hooks = shared))
            dbg.runMethod("Lcom/jdex/crypto/App;->run()Ljava/lang/String;", pauseAtEntry = false)
            assertTrue(dbg.awaitFinished(5000))
            assertEquals("SHARED", dbg.returnValue())
        }
    }
}
