package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NativeStringDecryptorTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = ByteArray(s.length) { (s[it].code xor key[it % 3].toInt()).toByte() }
    private fun so() = NativeStringDecryptorTest::class.java.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()

    @Test
    fun identifiesStringReturningCandidatesByJniSignature() {
        NativeStringDecryptor(so()).use { sd ->
            val byName = sd.candidates().associateBy { it.name }
            assertEquals(NativeStringDecryptor.ArgKind.BYTES, byName["decryptBytes"]?.argKind)
            assertEquals(NativeStringDecryptor.ArgKind.STRING, byName["decryptString"]?.argKind)
        }
    }

    @Test
    fun recoversPlaintextByRunningTheNativeDecryptor() {
        NativeStringDecryptor(so()).use { sd ->
            val byName = sd.candidates().associateBy { it.name }
            val cls = "com/jdex/crypto/Native"
            assertEquals("hello", sd.recover(cls, byName.getValue("decryptBytes"), enc("hello")))
            assertEquals("secret", sd.recover(cls, byName.getValue("decryptString"), String(enc("secret"), Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun recoversViaNativeToJavaCallback() {
        var asked: String? = null
        val bridge = JavaBridge { cls, name, sig, _, _ -> asked = "$cls#$name$sig"; "KEY" }
        NativeStringDecryptor(so(), bridge).use { sd ->
            val c = sd.candidates().first { it.name == "decryptWithJavaKey" }
            assertEquals("hello", sd.recover("com/jdex/crypto/Native", c, enc("hello")))
        }
        assertEquals("com/jdex/crypto/Native#key()Ljava/lang/String;", asked)
    }

    @Test
    fun annotatesDexCallSiteWithRecoveredPlaintext() {
        val dexBytes = NativeStringDecryptorTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(dexBytes) }
        val src = DexInputSource.load(dex)
        NativeStringDecryptor(so()).use { sd ->
            val anns = sd.recoverCallSites(src.allMethods())
            val app = anns.single { it.descriptor.startsWith("Lcom/jdex/crypto/App;") }
            assertEquals("\"hello\"", app.text)
        }
    }

    @Test
    fun doesNotFlagNonStringNatives() {
        NativeStringDecryptor(NativeStringDecryptorTest::class.java.getResourceAsStream("/jni/libjnitest_arm64-v8a.so")!!.readBytes()).use { sd ->
            assertTrue(sd.candidates().none { it.signature.contains("I") && !it.signature.endsWith(")Ljava/lang/String;") })
            assertTrue(sd.candidates().all { it.signature.endsWith(")Ljava/lang/String;") })
        }
    }
}
