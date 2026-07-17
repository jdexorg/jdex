package io.github.nitanmarcel.jdex.disasm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class NativeEmuHookTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = ByteArray(s.length) { (s[it].code xor key[it % 3].toInt()).toByte() }
    private fun so() = javaClass.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()
    private val cls = "com/jdex/crypto/Native"
    private val sig = "decryptBytes([B)Ljava/lang/String;"

    @Test
    fun functionHookReadsArgsAndLeavesResultUntouched() {
        var entered = false
        var argPtr = 0L
        NativeEmuController(so(), is64 = true).use { c ->
            val addr = c.symbolAddress("decryptBytes")!!
            c.hook(addr, onEnter = { ctx -> entered = true; argPtr = ctx.arg(2) }, onLeave = null)
            c.callStatic(cls, sig, enc("hello"))
            assertTrue(c.awaitFinished(10_000))
            assertTrue(entered, "on_enter must fire at the function")
            assertNotEquals(0L, argPtr, "the jbyteArray arg register must be readable in the hook")
            assertEquals("hello", c.returnValue, "an observe-only hook must not change the result")
        }
    }

    @Test
    fun replaceSkipsTheOriginalFunction() {
        NativeEmuController(so(), is64 = true).use { c ->
            val addr = c.symbolAddress("decryptBytes")!!
            c.replace(addr) { 0L }
            c.callStatic(cls, sig, enc("hello"))
            assertTrue(c.awaitFinished(10_000))
            assertNull(c.returnValue, "returning 0 (null jstring) proves the original body was skipped")
        }
    }

    @Test
    fun syscallInterceptorFiresAndOverridesTheReturn() {
        val seen = ConcurrentHashMap<Long, Int>()
        NativeEmuController(so(), is64 = true).use { c ->
            c.onSyscall { ctx ->
                seen.merge(ctx.number, 1, Int::plus)
                if (ctx.number == 172L) { ctx.setRet(31337L); true } else false
            }
            val getpid = c.symbolAddress("getpid") ?: error("libc getpid must resolve via deps")
            val ret = c.callAddressBlocking(getpid, emptyList())
            assertEquals(31337L, (ret as? Number)?.toLong(), "set_ret + handled must override the syscall result")
            assertEquals(1, seen[172L], "the interceptor must see the getpid syscall (nr 172)")
        }
    }
}
