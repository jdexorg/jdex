package io.github.nitanmarcel.jdex.exec.input

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.model.StringRef
import jadx.api.plugins.input.insns.Opcode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexInputSourceTest {

    private fun fixture(): DexInputSource = Fixtures.sample()

    @Test fun shortIdFormat() {
        assertEquals(
            "f(ILjava/lang/String;)V",
            MethodRef("LT;", "f", listOf("I", "Ljava/lang/String;"), "V").shortId,
        )
    }

    @Test fun decodesFixtureClass() {
        val src = fixture()

        val info = src.classInfo("LSample;")
        assertNotNull(info)
        assertEquals("Ljava/lang/Object;", info!!.superType)

        val add = src.method("LSample;", "add(II)I")
        assertNotNull(add)
        assertTrue(add!!.isStatic)
        assertTrue(add.insns.any { it.opcode == Opcode.ADD_INT })
        for (ins in add.insns) assertTrue(ins.offset in add.offsetToIndex)

        val hello = src.method("LSample;", "hello()Ljava/lang/String;")
        assertNotNull(hello)
        assertTrue(hello!!.insns.any { it.ref == StringRef("hi") })

        val makeArr = src.method("LSample;", "makeArr()[I")
        assertNotNull(makeArr)
        assertTrue(makeArr!!.insns.any { it.opcode == Opcode.NEW_ARRAY })

        val loop = src.method("LSample;", "loop(I)I")
        assertNotNull(loop)
        val branch = loop!!.insns.firstOrNull { it.opcode == Opcode.GOTO || it.opcode.name.startsWith("IF_") }
        assertNotNull(branch)
        assertTrue(branch!!.target in loop.offsetToIndex)

        assertTrue(src.allMethods().any { m -> m.insns.any { it.ref is MethodRef } })
    }
}
