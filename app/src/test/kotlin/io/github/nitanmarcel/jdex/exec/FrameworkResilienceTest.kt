package io.github.nitanmarcel.jdex.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameworkResilienceTest {

    @Test fun continuesPastUnstubbedFrameworkCall() {
        val src = Fixtures.frameworkCall()
        val m = src.method("Lt/Fw;", "afterFrameworkCall()I")!!
        assertEquals(42, Vm(src).invoke(m))
    }
}
