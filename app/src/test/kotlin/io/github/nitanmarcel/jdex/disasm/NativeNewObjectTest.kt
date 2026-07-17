package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeNewObjectTest {

    private fun so() = javaClass.getResourceAsStream("/jni/libemunew_arm64-v8a.so")!!.readBytes()

    @Test
    fun nativeNewObjectRunsInitAndInstanceCall() {
        NativeEmulator(so(), is64 = true, bridge = VmJavaBridge(Fixtures.emufix(), world = EmuWorld())).use { emu ->
            val out = emu.callString("com/jdex/emunew/Trigger", "buildTag()Ljava/lang/String;", null, listOf())
            assertEquals("hi", out)
        }
    }
}
