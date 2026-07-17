package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmuObjectScriptingTest {

    @Test
    fun emuObjectUnwrapsAsResolveArg() {
        val world = EmuWorld()
        val ctl = EmulatorDebuggerControl(source = { Fixtures.emufix() }, world = { world })
        val o = ctl.newObject("LEmuFix;", "(Ljava/lang/String;I)V", listOf("hi", 5))!!
        val r = ctl.resolve("LEmuFix;->check(LEmuFix;)I", listOf(o))
        assertEquals(6, r["return"])
    }
}
