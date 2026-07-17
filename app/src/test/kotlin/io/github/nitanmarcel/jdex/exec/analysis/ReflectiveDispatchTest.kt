package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReflectiveDispatchTest {
    private val src = Fixtures.reflect()
    private val rd = ReflectiveDispatch(src)
    private val sid = "n(ILjava/lang/Object;)Ljava/lang/Object;"

    @Test
    fun resolvesIndexedFieldAccessByEmulation() {
        val name = rd.resolve("LDisp;", sid, 0x100)
        assertNotNull(name)
        assertTrue(name!!.isField)
        assertEquals("LBean;", name.owner)
        assertEquals("name", name.member)
        assertEquals("Ljava/lang/String;", name.memberType)

        val age = rd.resolve("LDisp;", sid, 0x101)
        assertEquals("age", age?.member)
        assertEquals("LBean;", age?.owner)
    }

    @Test
    fun unmappedIndexResolvesToNothing() {
        assertEquals(null, rd.resolve("LDisp;", sid, 0x999))
    }

    @Test
    fun reflectiveFieldGetReadsConcretelySetInstanceField() {
        val m = src.method("LAccess;", "roundtrip(Ljava/lang/String;)Ljava/lang/Object;")!!
        assertEquals("hi", Vm(src).invoke(m, listOf("hi"), null))
    }

    @Test
    fun reflectiveFieldSetWritesInstanceFieldReadBackDirectly() {
        val m = src.method("LAccess;", "setViaReflection(Ljava/lang/String;)Ljava/lang/String;")!!
        assertEquals("bye", Vm(src).invoke(m, listOf("bye"), null))
    }
}
