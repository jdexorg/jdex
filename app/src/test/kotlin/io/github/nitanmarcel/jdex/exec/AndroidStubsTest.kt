package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidStubsTest {

    private val stubs = AndroidStubs()

    private fun ref(cls: String, name: String, args: List<String>, ret: String) = MethodRef(cls, name, args, ret)

    @Test
    fun base64DecodeProducesPlaintext() {
        val r = ref("Landroid/util/Base64;", "decode", listOf("Ljava/lang/String;", "I"), "[B")
        assertEquals("hello", String(stubs.callStatic(r, listOf("aGVsbG8=", 0)) as ByteArray))
    }

    @Test
    fun base64EncodeRoundTrips() {
        val r = ref("Landroid/util/Base64;", "encodeToString", listOf("[B", "I"), "Ljava/lang/String;")
        val enc = stubs.callStatic(r, listOf("hello".toByteArray(), 2)) as String
        assertEquals("hello", String(java.util.Base64.getMimeDecoder().decode(enc)))
    }

    @Test
    fun buildFieldsComeFromEnv() {
        assertEquals(33, stubs.field("Landroid/os/Build\$VERSION;", "SDK_INT"))
        assertSame(NotHandled, stubs.field("Landroid/os/Build;", "NOPE"))
    }

    @Test
    fun unimplementedFrameworkMethodThrows() {
        val r = ref("Landroid/text/TextUtils;", "isEmpty", listOf("Ljava/lang/CharSequence;"), "Z")
        assertThrows(StubNotImplemented::class.java) { stubs.callStatic(r, listOf("x")) }
    }

    @Test
    fun nonFrameworkUnknownClassFallsThroughNotThrows() {
        val r = ref("Lcom/acme/Crypto;", "x", listOf("I"), "I")
        assertSame(NotHandled, stubs.callStatic(r, listOf(1)))
    }

    @Test
    fun registeredHandlerOverridesStub() {
        stubs.registerMethod("Landroid/text/TextUtils;", "isEmpty") { _, a ->
            (a.getOrNull(0) as? CharSequence).isNullOrEmpty()
        }
        val r = ref("Landroid/text/TextUtils;", "isEmpty", listOf("Ljava/lang/CharSequence;"), "Z")
        assertEquals(true, stubs.callStatic(r, listOf("")))
        assertEquals(false, stubs.callStatic(r, listOf("x")))
    }

    @Test
    fun hostExecRoutesStubsHavocsUnknownAndThrowsOnMissing() {
        val he = HostExec(HostBoundary())
        val decode = ref("Landroid/util/Base64;", "decode", listOf("Ljava/lang/String;", "I"), "[B")
        assertEquals("hi", String(he.invokeStatic(decode, listOf("aGk=", 0)) as ByteArray))
        assertTrue(he.invokeStatic(decode, listOf(UnknownVal("Ljava/lang/String;"), 0)) is UnknownVal)

        val missing = ref("Landroid/text/TextUtils;", "join", listOf("Ljava/lang/CharSequence;", "[Ljava/lang/Object;"), "Ljava/lang/String;")
        assertThrows(StubNotImplemented::class.java) { he.invokeStatic(missing, listOf(",", arrayOf<Any?>("a", "b"))) }
    }
}
