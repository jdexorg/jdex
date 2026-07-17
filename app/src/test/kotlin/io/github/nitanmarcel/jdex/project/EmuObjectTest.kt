package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmuObjectTest {

    @Test
    fun constructRunsInitAndCallsInstanceMethod() {
        val w = EmuWorld()
        val o = constructEmuObject(Fixtures.emufix(), w, "LEmuFix;", "(Ljava/lang/String;I)V", listOf("hi", 5))
        assertEquals(5, o.get("v"))
        assertEquals("hi", o.get("name"))
        assertEquals(10, o.call("doubled()I", listOf()))
        o.set("v", 10)
        assertEquals(20, o.call("doubled()I", listOf()))
        assertEquals("hi", o.call("tag()Ljava/lang/String;", listOf()))
    }

    @Test
    fun callSeesSharedWorldStatics() {
        val w = EmuWorld()
        val s = Fixtures.emufix()
        Vm(s, statics = w.statics).invoke(s.method("LEmuFix;", "setBase(I)V")!!, listOf(9))
        val o = constructEmuObject(s, w, "LEmuFix;", "(Ljava/lang/String;I)V", listOf("x", 5))
        assertEquals(14, o.call("plusBase()I", listOf()))
    }
}
