package io.github.nitanmarcel.jdex.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineTypeFidelityTest {
    private val src = Fixtures.obfuscapk()
    private val vm = Vm(src)
    private fun m(id: String) = src.method("Lcom/jdex/cfgdemo/MainActivity;", id)!!

    @Test fun charReturnYieldsKotlinChar() {
        val r = vm.invoke(m("grade(I)C"), listOf(85))
        assertTrue(r is Char) { "char-returning method must yield Kotlin Char, got ${r?.javaClass?.simpleName}: $r" }
        assertEquals('B', r)
    }

    @Test fun longReturnStaysLong() {
        assertEquals(55L, vm.invoke(m("fib(I)J"), listOf(10)))
    }

    @Test fun intReturnStaysInt() {
        assertEquals(111, vm.invoke(m("collatzSteps(I)I"), listOf(27)))
    }

    private val tf = Fixtures.typeFidelity()
    private val tvm = Vm(tf)
    private fun t(id: String) = tf.method("LTF;", id)!!

    @Test fun intToCharConversionYieldsChar() {
        assertEquals('A', tvm.invoke(t("toChar(I)C"), listOf(65)))
    }

    @Test fun intToByteConversionYieldsByte() {
        assertEquals((-56).toByte(), tvm.invoke(t("toByte(I)B"), listOf(200)))
    }

    @Test fun intToShortConversionYieldsShort() {
        assertEquals((-25536).toShort(), tvm.invoke(t("toShort(I)S"), listOf(40000)))
    }

    @Test fun charParamReboxedFromIntArg() {
        assertEquals('A', tvm.invoke(t("echo(C)C"), listOf(65)))
    }

    @Test fun charStaticFieldRoundTrip() {
        assertEquals('A', tvm.invoke(t("fieldRoundTrip(I)C"), listOf(65)))
    }

    @Test fun wideStaticFieldStaysLong() {
        assertEquals(5L, tvm.invoke(t("longField(J)J"), listOf(5L)))
    }

    @Test fun booleanReturnYieldsBoolean() {
        assertEquals(true, tvm.invoke(t("flag(I)Z"), listOf(5)))
    }
}
