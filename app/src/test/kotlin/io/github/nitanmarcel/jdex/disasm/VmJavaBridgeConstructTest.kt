package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmJavaBridgeConstructTest {

    @Test
    fun initReturnsConstructedObject() {
        val bridge = VmJavaBridge(Fixtures.emufix(), world = EmuWorld())
        val o = bridge.call("EmuFix", "<init>", "(Ljava/lang/String;I)V", listOf("hi", 5), null)
        assertTrue(o is DvmObject && o.type == "LEmuFix;")
        assertEquals(5, (o as DvmObject).fields["LEmuFix;.v"])
    }
}
