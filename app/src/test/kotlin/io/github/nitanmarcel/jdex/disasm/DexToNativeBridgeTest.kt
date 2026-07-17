package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DexToNativeBridgeTest {

    private fun res(name: String) = DexToNativeBridgeTest::class.java.getResourceAsStream(name)!!.readBytes()

    @Test
    fun dexMethodCrossesIntoNativeAndBack() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)
        assertTrue(src.isNative("Lcom/jdex/crypto/Native;", "decryptBytes([B)Ljava/lang/String;"), "the native method must be recognized")

        DexToNativeBridge(src, listOf(res("/jni/libjnicrypt_arm64-v8a.so"))).use { bridge ->
            val vm = Vm(src, nativeBridge = bridge)
            val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
            assertEquals("hello", vm.invoke(run))
        }
    }

    @Test
    fun withoutBridgeTheNativeCallIsStubbed() {
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(res("/jni/jnicrypt-caller.dex")) }
        val src = DexInputSource.load(dex)
        val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
        assertEquals(null, (Vm(src).invoke(run) as? String)?.takeIf { it == "hello" })
    }
}
