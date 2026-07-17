package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NativeEmulatorScriptingTest {

    private val key = "KEY".toByteArray(Charsets.US_ASCII)
    private fun enc(s: String) = List(s.length) { (s[it].code xor key[it % 3].toInt()) }

    private fun engine(): ScriptEngine {
        val so = NativeEmulatorScriptingTest::class.java.getResourceAsStream("/jni/libjnicrypt_arm64-v8a.so")!!.readBytes()
        val src = Fixtures.sample()
        val control = NativeEmulatorDebuggerControl(lib = { if (it == "libjnicrypt.so") so else null }, source = { src })
        return ScriptEngine(ScriptApi(session = { null }, nativeEmu = control), ByteArrayOutputStream())
    }

    @Test
    fun decryptsNativeStringViaPython() {
        engine().use { e ->
            e.eval("cipher = ${enc("hello")}")
            assertEquals("hello", e.eval("jdex.native_emu.decrypt('libjnicrypt.so', 'com/jdex/crypto/Native', 'decryptBytes([B)Ljava/lang/String;', cipher)").asString())
        }
    }

    @Test
    fun runStopInspectStepResumeViaPython() {
        engine().use { e ->
            e.eval("jdex.native_emu.run('libjnicrypt.so', 'com/jdex/crypto/Native', 'decryptBytes([B)Ljava/lang/String;', [[${enc("hi").joinToString(",")}]])")
            assertTrue(e.eval("jdex.native_emu.await_stop()").asBoolean())
            assertEquals("stopped", e.eval("jdex.native_emu.state()").asString())
            assertTrue(e.eval("jdex.native_emu.symbol('decryptBytes') > 0").asBoolean())
            assertTrue(e.eval("'pc' in jdex.native_emu.registers()").asBoolean())
            assertTrue(e.eval("len(jdex.native_emu.frames()) > 0").asBoolean())
            e.eval("jdex.native_emu.step_over()")
            assertTrue(e.eval("jdex.native_emu.await_stop()").asBoolean())
            e.eval("jdex.native_emu.resume()")
            assertTrue(e.eval("jdex.native_emu.await_finished()").asBoolean())
            assertEquals("hi", e.eval("jdex.native_emu.return_value()").asString())
        }
    }

    @Test
    fun functionHookCallbackRunsFromPython() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            e.eval("box = {'arg': 0}")
            e.eval("addr = jdex.native_emu.symbol('decryptBytes')")
            e.eval("jdex.native_emu.hook(addr, on_enter=lambda ctx: box.__setitem__('arg', ctx.arg(2)))")
            val r = e.eval("jdex.native_emu.decrypt('libjnicrypt.so', 'com/jdex/crypto/Native', 'decryptBytes([B)Ljava/lang/String;', ${enc("hello")})")
            assertEquals("hello", r.asString())
            assertTrue(e.eval("box['arg'] != 0").asBoolean(), "the Python hook must have read the jbyteArray arg register")
        }
    }

    @Test
    fun replaceCallbackFromPythonSkipsOriginal() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            e.eval("addr = jdex.native_emu.symbol('decryptBytes')")
            e.eval("jdex.native_emu.replace(addr, lambda ctx: 0)")
            val r = e.eval("jdex.native_emu.decrypt('libjnicrypt.so', 'com/jdex/crypto/Native', 'decryptBytes([B)Ljava/lang/String;', ${enc("hello")})")
            assertTrue(r.isNull, "replacing the function with a 0/null return must skip the real decryption")
        }
    }

    @Test
    fun syscallInterceptorFromPythonOverridesReturn() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            e.eval("jdex.native_emu.on_syscall(lambda ctx: (ctx.set_ret(31337), True)[1] if ctx.number() == 172 else False)")
            e.eval("g = jdex.native_emu.symbol('getpid')")
            assertEquals(31337L, e.eval("jdex.native_emu.call(g)").asLong(), "the Python syscall hook must override getpid")
        }
    }

    @Test
    fun modulesAndReverseSymbolFromPython() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            assertTrue(e.eval("len(jdex.native_emu.modules()) > 0").asBoolean(), "modules() must list loaded modules")
            assertTrue(e.eval("all(m['base'] > 0 for m in jdex.native_emu.modules())").asBoolean(), "each module reports a base")
            e.eval("addr = jdex.native_emu.symbol('decryptBytes')")
            assertEquals("decryptBytes", e.eval("jdex.native_emu.symbol_at(addr)['name']").asString(), "symbol_at names the function")
            assertEquals(0L, e.eval("jdex.native_emu.symbol_at(addr)['offset']").asLong(), "offset 0 at the symbol start")
            assertEquals(4L, e.eval("jdex.native_emu.symbol_at(addr + 4)['offset']").asLong(), "offset from the symbol start")
        }
    }

    @Test
    fun memWatchCallbackRunsFromPython() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            e.eval("buf = jdex.native_emu.malloc(4)")
            e.eval("jdex.native_emu.mem_write(buf, [1, 2, 3, 4])")
            e.eval("box = {'addr': -1}")
            e.eval("jdex.native_emu.mem_watch(buf, buf + 4, on_read=lambda acc: box.__setitem__('addr', acc.address()))")
            e.eval("s = jdex.native_emu.symbol('jdex_sum')")
            assertEquals(10L, e.eval("jdex.native_emu.call(s, buf, 4)").asLong(), "jdex_sum sums the watched buffer")
            assertTrue(e.eval("buf <= box['addr'] < buf + 4").asBoolean(), "the Python read watchpoint observed a buffer read")
        }
    }

    @Test
    fun blockTraceCallbackRunsFromPython() {
        engine().use { e ->
            e.eval("jdex.native_emu.load('libjnicrypt.so')")
            e.eval("base = jdex.native_emu.module_base()")
            e.eval("m = [x for x in jdex.native_emu.modules() if x['base'] == base][0]")
            e.eval("box = {'blocks': 0}")
            e.eval("tid = jdex.native_emu.trace(lambda b: box.__setitem__('blocks', box['blocks'] + 1), base, base + m['size'])")
            e.eval("jdex.native_emu.decrypt('libjnicrypt.so', 'com/jdex/crypto/Native', 'decryptBytes([B)Ljava/lang/String;', ${enc("hi")})")
            assertTrue(e.eval("box['blocks'] > 0").asBoolean(), "the Python block-trace callback fired during emulation")
            assertTrue(e.eval("jdex.native_emu.unhook(tid)").asBoolean(), "unhook removes the trace")
        }
    }
}
