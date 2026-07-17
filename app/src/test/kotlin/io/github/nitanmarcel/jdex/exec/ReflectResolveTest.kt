package io.github.nitanmarcel.jdex.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReflectResolveTest {

    private val src = Fixtures.reflect()

    private fun run(shortId: String): Any? {
        val m = src.method("LRefl2;", shortId)!!
        return Vm(src).invoke(m, emptyList(), null)
    }

    @Test
    fun resolvesInheritedStaticMethod() {
        assertEquals("inherited", run("viaInheritedStatic()Ljava/lang/String;"))
    }

    @Test
    fun resolvesFieldReflection() {
        assertEquals("secret", run("viaField()Ljava/lang/Object;"))
    }
}
