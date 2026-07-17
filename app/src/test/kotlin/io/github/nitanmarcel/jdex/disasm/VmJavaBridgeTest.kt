package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VmJavaBridgeTest {

    @Test
    fun runsDexMethodForNativeCallback() {
        val bridge = VmJavaBridge(Fixtures.sample())
        assertEquals(6, bridge.call("Sample", "sum3", "(III)I", listOf(1, 2, 3), null))
    }

    @Test
    fun returnsNullForUnknownMethod() {
        val bridge = VmJavaBridge(Fixtures.sample())
        assertNull(bridge.call("Sample", "nope", "()I", emptyList(), null))
    }
}
