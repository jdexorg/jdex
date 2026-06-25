package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.ElfArch
import io.github.nitanmarcel.jdex.disasm.NativeJni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BindingMapTest {
    private fun analysis(registered: Map<Long, NativeJni.JniNative>, envFns: Map<Long, Map<Int, NativeJni.Tag>>) =
        NativeJni.Analysis(registered, emptySet(), envFns, 8, ElfArch.ARM, true)

    @Test fun resolvesRegisterNativesByNameAndSignature() {
        val a = analysis(mapOf(0x15b0L to NativeJni.JniNative("nativeKey", "(I)I")), emptyMap())
        val dex = listOf(DexNativeMethod("Lcom/x/A;->nativeKey(I)I", "com/x/A", "nativeKey", "(I)I"))
        val m = BindingMap(); m.add("libjdexdbg.so", a, emptyMap(), dex)
        assertEquals("libjdexdbg.so" to 0x15b0L, m.nativeEntry("Lcom/x/A;->nativeKey(I)I"))
        assertNull(m.nativeEntry("Lcom/x/A;->other()V"))
    }

    @Test fun resolvesJavaExportSymbol() {
        val dex = listOf(DexNativeMethod("Lcom/x/A;->nativeFoo()V", "com/x/A", "nativeFoo", "()V"))
        val m = BindingMap(); m.add("lib.so", analysis(emptyMap(), emptyMap()), mapOf("Java_com_x_A_nativeFoo" to 0x2000L), dex)
        assertEquals("lib.so" to 0x2000L, m.nativeEntry("Lcom/x/A;->nativeFoo()V"))
    }

    @Test fun exposesCallSites() {
        val a = analysis(emptyMap(), mapOf(0x900L to mapOf(0 to NativeJni.Tag.ENV)))
        val m = BindingMap(); m.add("lib.so", a, emptyMap(), emptyList())
        assertEquals(setOf(0x900L), m.callSites("lib.so"))
    }
}
