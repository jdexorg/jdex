package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EmulationArgsTest {

    @Test
    fun parsesParamTypes() {
        assertEquals(
            listOf("I", "[B", "Ljava/lang/String;", "Landroid/content/pm/ApplicationInfo;"),
            parseParamTypes("Lc;->f(I[BLjava/lang/String;Landroid/content/pm/ApplicationInfo;)V"),
        )
        assertEquals(emptyList<String>(), parseParamTypes("Lc;->g()V"))
    }

    @Test
    fun primitiveDefaultsAndParsing() {
        val types = listOf("I", "J", "Z", "C")
        val vals = argValues(types, listOf(ArgInput.Text(""), ArgInput.Text("0x10"), ArgInput.Bool(true), ArgInput.Text("A")))
        assertEquals(listOf<Any?>(0, 16L, true, 'A'), vals)
    }

    @Test
    fun emptyStringAndObjectAreNull() {
        assertEquals(
            listOf<Any?>(null, null),
            argValues(listOf("Ljava/lang/String;", "Landroid/content/pm/ApplicationInfo;"), listOf(ArgInput.Text(""), ArgInput.Null)),
        )
        assertEquals(listOf<Any?>("hi"), argValues(listOf("Ljava/lang/String;"), listOf(ArgInput.Text("hi"))))
    }

    @Test
    fun byteArrayFromElementsFileAndEmpty() {
        assertArrayEquals(byteArrayOf(10, 20, 30), argValues(listOf("[B"), listOf(ArgInput.Elements(listOf("10", "20", "30"))))[0] as ByteArray)
        assertArrayEquals(byteArrayOf(1, 2), argValues(listOf("[B"), listOf(ArgInput.Bytes(byteArrayOf(1, 2))))[0] as ByteArray)
        assertNull(argValues(listOf("[B"), listOf(ArgInput.Elements(emptyList())))[0])
    }

    @Test
    fun missingInputsTolerated() {
        assertEquals(listOf<Any?>(0, null), argValues(listOf("I", "Ljava/lang/String;"), emptyList()))
    }

    @Test
    fun receiverForInstanceVsStatic() {
        val bytes = EmulationArgsTest::class.java.getResourceAsStream("/jni/jnicrypt-caller.dex")!!.readBytes()
        val dex = File.createTempFile("caller", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val src = DexInputSource.load(dex)
        val run = src.method("Lcom/jdex/crypto/App;", "run()Ljava/lang/String;")!!
        assertNull(emulationReceiver(run, src))
        val init = src.methodsOf("Lcom/jdex/crypto/App;").first { it.ref.name == "<init>" }
        val recv = emulationReceiver(init, src)
        assertTrue(recv is DvmObject && recv.type == "Lcom/jdex/crypto/App;")
    }
}
