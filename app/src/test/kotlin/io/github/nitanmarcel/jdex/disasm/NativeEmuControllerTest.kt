package io.github.nitanmarcel.jdex.disasm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class NativeEmuControllerTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = ByteArray(s.length) { (s[it].code xor key[it % 3].toInt()).toByte() }
    private fun so() = NativeEmuControllerTest::class.java.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()
    private val cls = "com/jdex/crypto/Native"
    private val sig = "decryptBytes([B)Ljava/lang/String;"

    @Test
    fun breakpointStepAndResume() {
        NativeEmuController(so(), is64 = true).use { ctrl ->
            val addr = ctrl.symbolAddress("decryptBytes") ?: error("decryptBytes not found")
            ctrl.addBreakpoint(addr)
            ctrl.callStatic("com/jdex/crypto/Native", "decryptBytes([B)Ljava/lang/String;", enc("hello"))

            assertTrue(ctrl.awaitStop(), "must pause at the breakpoint")
            assertEquals(NativeEmuState.STOPPED, ctrl.state)
            assertEquals(addr, ctrl.pc(), "paused at decryptBytes entry")
            assertNotEquals(0L, ctrl.registers()["x2"], "the jbyteArray arg must be a live pointer")

            val entry = ctrl.pc()
            ctrl.stepInto()
            assertTrue(ctrl.awaitStop(), "must pause after one instruction")
            assertNotEquals(entry, ctrl.pc(), "single-step must advance the pc")

            ctrl.resume()
            assertTrue(ctrl.awaitFinished(), "must run to completion after resume")
            assertEquals("hello", ctrl.returnValue)
        }
    }

    @Test
    fun runToStopsAtAnAddress() {
        NativeEmuController(so(), is64 = true).use { ctrl ->
            val addr = ctrl.symbolAddress("decryptBytes") ?: error("decryptBytes not found")
            ctrl.armRunTo(addr)
            ctrl.callStatic("com/jdex/crypto/Native", "decryptBytes([B)Ljava/lang/String;", enc("hello"))
            assertTrue(ctrl.awaitStop(), "run-to stops at the address")
            assertEquals(addr, ctrl.pc())
        }
    }

    @Test
    fun memWatchObservesBufferReads() {
        val reads = ConcurrentLinkedQueue<Long>()
        NativeEmuController(so(), is64 = true).use { ctrl ->
            val sum = ctrl.symbolAddress("jdex_sum") ?: error("jdex_sum not found")
            val buf = ctrl.malloc(4)
            assertNotEquals(0L, buf, "malloc must return a buffer")
            ctrl.writeMemory(buf, byteArrayOf(1, 2, 3, 4))
            ctrl.memWatch(buf, buf + 4, onRead = { acc -> reads.add(acc.address()) }, onWrite = null)

            val r = ctrl.callAddressBlocking(sum, listOf(buf, 4))
            assertEquals(10L, (r as? Number)?.toLong(), "jdex_sum must sum the buffer bytes")
            assertTrue(reads.any { it in buf until buf + 4 }, "the read watchpoint must observe the buffer reads")
        }
    }

    @Test
    fun blockTraceFiresDuringEmulation() {
        val blocks = AtomicInteger(0)
        NativeEmuController(so(), is64 = true).use { ctrl ->
            ctrl.trace(1L, 0L) { blocks.incrementAndGet() }
            ctrl.callStatic(cls, sig, enc("hi"))
            assertTrue(ctrl.awaitFinished(10_000))
            assertTrue(blocks.get() > 0, "block trace must fire for executed blocks")
        }
    }

    @Test
    fun unhookStopsABackendHook() {
        val blocks = AtomicInteger(0)
        NativeEmuController(so(), is64 = true).use { ctrl ->
            val id = ctrl.trace(1L, 0L) { blocks.incrementAndGet() }
            assertTrue(ctrl.unhook(id), "unhook must report the trace was removed")
            ctrl.callStatic(cls, sig, enc("hi"))
            assertTrue(ctrl.awaitFinished(10_000))
            assertEquals(0, blocks.get(), "an unhooked trace must not fire")
        }
    }

    @Test
    fun modulesAndReverseSymbolResolution() {
        NativeEmuController(so(), is64 = true).use { ctrl ->
            val mods = ctrl.modules()
            assertTrue(mods.isNotEmpty(), "loaded modules must be listed")
            assertTrue(mods.all { (it["base"] as? Long ?: 0L) > 0L }, "each module reports a load base")

            val addr = ctrl.symbolAddress("decryptBytes") ?: error("decryptBytes not found")
            val sym = ctrl.symbolAt(addr)
            assertNotNull(sym, "symbolAt must resolve a known function address")
            assertEquals("decryptBytes", sym!!["name"], "reverse lookup names the function")
            assertEquals(0L, sym["offset"], "at the symbol start the offset is 0")
            assertEquals(4L, ctrl.symbolAt(addr + 4)?.get("offset"), "an inside address reports the offset from the symbol start")
        }
    }
}
