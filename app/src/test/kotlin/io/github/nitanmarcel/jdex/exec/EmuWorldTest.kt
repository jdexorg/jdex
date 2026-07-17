package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmuWorldTest {

    @Test
    fun staticWriteVisibleAcrossVmsSharingWorld() {
        val world = EmuWorld()
        val s = Fixtures.emufix()
        Vm(s, statics = world.statics).invoke(s.method("LEmuFix;", "setBase(I)V")!!, listOf(9))
        val recv = DvmObject("LEmuFix;")
        Vm(s, statics = world.statics).invoke(s.method("LEmuFix;", "<init>(Ljava/lang/String;I)V")!!, listOf("x", 5), recv)
        val got = Vm(s, statics = world.statics).invoke(s.method("LEmuFix;", "plusBase()I")!!, listOf(), recv)
        assertEquals(14, got)
    }
}
