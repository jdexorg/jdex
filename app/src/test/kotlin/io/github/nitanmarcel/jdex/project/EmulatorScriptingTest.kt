package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.debug.DebugState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class EmulatorScriptingTest {

    private fun engine(): ScriptEngine {
        val src = Fixtures.sample()
        val api = ScriptApi(session = { null }, emu = EmulatorDebuggerControl(source = { src }))
        return ScriptEngine(api, ByteArrayOutputStream())
    }

    @Test
    fun runStepInspectResumeViaPython() {
        engine().use { e ->
            e.eval("jdex.emu.run('LSample;->loop(I)I', [5])")
            assertTrue(e.eval("jdex.emu.await_stop()").asBoolean())
            assertEquals("stopped", e.eval("jdex.emu.state()").asString())
            assertTrue(e.eval("len(jdex.emu.variables(0))").asInt() > 0)
            e.eval("jdex.emu.resume()")
            assertTrue(e.eval("jdex.emu.await_finished()").asBoolean())
            assertEquals(10, e.eval("jdex.emu.return_value()").asInt())
        }
    }

    @Test
    fun replaceRegisterViaPython() {
        engine().use { e ->
            e.eval(
                """
                jdex.emu.run('LSample;->loop(I)I', [5])
                jdex.emu.await_stop()
                for v in jdex.emu.variables(0):
                    if v['value'] == '5' and v['edit_key']:
                        jdex.emu.set_value(v['edit_key'], '3')
                        break
                jdex.emu.resume()
                jdex.emu.await_finished()
                result = jdex.emu.return_value()
                """.trimIndent()
            )
            assertEquals(3, e.eval("result").asInt())
        }
    }

    @Test
    fun resolveViaPython() {
        engine().use { e ->
            assertEquals(6, e.eval("jdex.emu.resolve('LSample;->sum3(III)I', [1, 2, 3])['return']").asInt())
            assertTrue(e.eval("jdex.emu.resolve('LSample;->pick(Z)I')['unknown']").asBoolean())
        }
    }

    @Test
    fun uiSessionSharesOneEmulatorAndResetsForReuse() {
        val src = Fixtures.sample()
        val control = EmulatorDebuggerControl(source = { src })
        val s1 = control.session()!!
        assertSame(s1, control.session()) { "session() must return the one shared debugger" }
        control.run("LSample;->loop(I)I", listOf(5))
        assertTrue(s1.awaitStop(), "pauses at entry")
        assertTrue(s1.state is DebugState.Stopped)
        assertTrue(s1.frames().isNotEmpty(), "UI frame/variable inspection reaches the live emulator")
        s1.resume()
        assertTrue(s1.awaitFinished())
        assertEquals(10, s1.returnValue())
        control.reset()
        assertNotSame(s1, control.session(), "reset() yields a fresh emulator for the next attach")
    }

    @Test
    fun registerStubAndFieldViaPython() {
        engine().use { e ->
            e.eval("jdex.emu.register_field('Landroid/os/Build;', 'MODEL', 'test-device')")
            e.eval("jdex.emu.register_stub('Landroid/text/TextUtils;', 'isEmpty', lambda r, a: not a[0])")
        }
    }

    private fun emufixEngine(): ScriptEngine {
        val src = Fixtures.emufix()
        val api = ScriptApi(session = { null }, emu = EmulatorDebuggerControl(source = { src }))
        return ScriptEngine(api, ByteArrayOutputStream())
    }

    @Test
    fun newObjectConstructInspectCallInjectViaPython() {
        emufixEngine().use { e ->
            e.eval(
                """
                w = jdex.emu.new('LEmuFix;', '(Ljava/lang/String;I)V', 'hi', 5)
                first = w.call('doubled()I')
                w.set('v', 10)
                second = w.call('doubled()I')
                nm = w.get('name')
                checked = jdex.emu.resolve('LEmuFix;->check(LEmuFix;)I', [w])['return']
                """.trimIndent()
            )
            assertEquals(10, e.eval("first").asInt())
            assertEquals(20, e.eval("second").asInt())
            assertEquals("hi", e.eval("nm").asString())
            assertEquals(11, e.eval("checked").asInt())
        }
    }

    @Test
    fun runWithoutPauseAtEntryFinishesWithoutResume() {
        engine().use { e ->
            e.eval(
                """
                jdex.emu.run('LSample;->loop(I)I', [5], pause_at_entry=False)
                jdex.emu.await_finished()
                r = jdex.emu.return_value()
                """.trimIndent()
            )
            assertEquals(10, e.eval("r").asInt())
        }
    }

    @Test
    fun hookInjectsEmuObjectAsArgViaPython() {
        emufixEngine().use { e ->
            e.eval(
                """
                w = jdex.emu.new('LEmuFix;', '(Ljava/lang/String;I)V', 'hi', 7)
                jdex.emu.hook('LEmuFix;->check(LEmuFix;)I', lambda c: c.set_arg(0, w))
                jdex.emu.run('LEmuFix;->viaCheck(LEmuFix;)I', [None], pause_at_entry=False)
                jdex.emu.await_finished()
                out = jdex.emu.return_value()
                """.trimIndent()
            )
            assertEquals(8, e.eval("out").asInt())
        }
    }
}
