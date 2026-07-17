package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class NativeInstanceCallbackTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = ByteArray(s.length) { (s[it].code xor key[it % 3].toInt()).toByte() }
    private fun so() = javaClass.getResourceAsStream("/jni/libjniinst_arm64-v8a.so")!!.readBytes()

    @Test
    fun nativeInstanceMethodCallsBackJavaInstanceMethodWithReceiver() {
        val receiver = DvmObject("Lcom/jdex/crypto/InstNative;").apply { fields["k"] = "KEY" }
        var seenReceiver: Any? = null
        val bridge = JavaBridge { _, name, _, _, recv ->
            if (name == "key") { seenReceiver = recv; (recv as? DvmObject)?.fields?.get("k") } else null
        }
        NativeEmulator(so(), is64 = true, bridge = bridge).use { emu ->
            val out = emu.callString("com/jdex/crypto/InstNative", "getKeyedValue([B)Ljava/lang/String;", receiver, listOf(enc("hello")))
            assertEquals("hello", out)
        }
        assertSame(receiver, seenReceiver)
    }
}
