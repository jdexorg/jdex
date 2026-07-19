package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.debug.EmulatorDebugger
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private object EmptySource : MethodSource {
    override fun method(classDesc: String, shortId: String): DexMethod? = null
    override fun classInfo(classDesc: String): ClassInfo? = null
    override fun methodsByName(classDesc: String, name: String): List<DexMethod> = emptyList()
    override fun methodsOf(classDesc: String): List<DexMethod> = emptyList()
    override fun allMethods(): List<DexMethod> = emptyList()
}

class FrameworkStubsTest {

    @Test
    fun resolvesFrameworkSignatures() {
        val src = FrameworkStubs.source
        assertNotNull(src, "hollowed android.jar stub must load")
        assertNotNull(src!!.method("Landroid/text/TextUtils;", "isEmpty(Ljava/lang/CharSequence;)Z"))
        assertNotNull(src.method("Landroid/util/Base64;", "decode(Ljava/lang/String;I)[B"))
        assertNull(src.method("Landroid/text/TextUtils;", "bogus()V"), "a non-existent signature must not resolve")
    }

    @Test
    fun routesRegisteredStub() {
        val android = AndroidStubs()
        android.registerMethod("Landroid/text/TextUtils;", "isEmpty") { _, a -> (a[0] as? String).isNullOrEmpty() }
        assertEquals(true, FrameworkStubs.call(android, "Landroid/text/TextUtils;", "isEmpty(Ljava/lang/CharSequence;)Z", null, listOf("")))
        assertEquals(false, FrameworkStubs.call(android, "Landroid/text/TextUtils;", "isEmpty(Ljava/lang/CharSequence;)Z", null, listOf("x")))
    }

    @Test
    fun unregisteredFrameworkMethodDegradesToUnknown() {
        val r = FrameworkStubs.call(AndroidStubs(), "Landroid/text/TextUtils;", "isEmpty(Ljava/lang/CharSequence;)Z", null, listOf("x"))
        assertTrue(r is UnknownVal, "an unstubbed framework method must degrade to Unknown, not throw")
    }

    @Test
    fun wrongSignatureFailsLoudly() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            FrameworkStubs.call(AndroidStubs(), "Landroid/text/TextUtils;", "isEmpty(I)Z", null, listOf(1))
        }
        assertTrue(ex.message!!.contains("framework member"), "a wrong signature must fail with a clear message")
    }

    @Test
    fun resolveFrameworkMethodViaStub() {
        val dbg = EmulatorDebugger(EmptySource)
        dbg.registerStub("Landroid/text/TextUtils;", "isEmpty") { _, a -> (a[0] as? String).isNullOrEmpty() }
        assertEquals(true, dbg.resolve("Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z", listOf("")).returnValue)
    }

    @Test
    fun newFrameworkObjectDoesNotThrow() {
        val obj = constructObject(EmptySource, EmuWorld(), "Landroid/os/Bundle;", "()V", emptyList())
        assertEquals("Landroid/os/Bundle;", obj.type)
    }

    @Test
    fun newFrameworkObjectWrongCtorFails() {
        assertThrows(IllegalArgumentException::class.java) {
            constructObject(EmptySource, EmuWorld(), "Landroid/os/Bundle;", "(III)V", emptyList())
        }
    }
}
