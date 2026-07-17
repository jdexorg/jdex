package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmuControlNewObjectTest {

    @Test
    fun controlConstructsAndCalls() {
        val world = EmuWorld()
        val ctl = EmulatorDebuggerControl(source = { Fixtures.emufix() }, world = { world })
        val o = ctl.newObject("LEmuFix;", "(Ljava/lang/String;I)V", listOf("hi", 5))!!
        assertEquals(10, o.call("doubled()I", listOf()))
    }
}
