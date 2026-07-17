package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InvokeCustomTest {

    private val src = Fixtures.strconcat()
    private fun m(sig: String) = src.method("Lt/Concat;", sig)!!

    @Test fun concatsLiteralsArgsAndConstants() {
        val vm = Vm(src)
        assertEquals("val=42!", vm.invoke(m("simple()Ljava/lang/String;")))
        assertEquals("42-7-true/end", vm.invoke(m("many(IJZ)Ljava/lang/String;"), listOf(42, 7L, true)))
        assertEquals("foobar", vm.invoke(m("justArgs(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), listOf("foo", "bar")))
    }

    @Test fun unknownArgYieldsUnknownNotAbort() {
        val r = Vm(src).invoke(m("many(IJZ)Ljava/lang/String;"), listOf(UnknownVal("I"), 7L, true))
        assert(r is UnknownVal) { "an unknown dynamic arg must degrade to Unknown, got $r" }
    }
}
