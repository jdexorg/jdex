package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.ElfArch
import io.github.nitanmarcel.jdex.disasm.NativeJni
import io.github.nitanmarcel.jdex.project.ApkSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BindingResolutionTest {
    private fun fixture(dir: File): File {
        val bytes = BindingResolutionTest::class.java.getResourceAsStream("/fixtures/jdex-debug-target.apk")!!.readBytes()
        return File(dir, "fixture.apk").apply { writeBytes(bytes) }
    }

    private fun dexNatives(session: ApkSession): List<DexNativeMethod> {
        val jadx = session.decompiler()
        val out = ArrayList<DexNativeMethod>()
        for (cls in jadx.root.classes) for (mth in cls.methods) if (mth.accessFlags.isNative) {
            val mi = mth.methodInfo
            val raw = mi.declClass.rawName.replace('.', '/')
            out.add(DexNativeMethod("L$raw;->${mi.shortId}", raw, mi.name, mi.shortId.removePrefix(mi.name)))
        }
        return out
    }

    @Test
    fun nativeMethodDescriptorsAreSlashSeparated(@TempDir dir: File) {
        val session = ApkSession.load(fixture(dir), "probe")
        val dex = dexNatives(session)
        assertTrue(dex.any { it.descriptor == "Lio/github/nitanmarcel/jdexdbg/MainActivity;->nativeAdd(II)I" }) { "got $dex" }
    }

    @Test
    fun bindingResolvesJavaExports(@TempDir dir: File) {
        val session = ApkSession.load(fixture(dir), "probe")
        val bm = BindingMap()
        bm.add("libjdexdbg.so",
            NativeJni.Analysis(emptyMap(), emptySet(), emptyMap(), 8, ElfArch.ARM, true),
            mapOf("Java_io_github_nitanmarcel_jdexdbg_MainActivity_nativeAdd" to 0x1658L),
            dexNatives(session))
        assertEquals("libjdexdbg.so" to 0x1658L,
            bm.nativeEntry("Lio/github/nitanmarcel/jdexdbg/MainActivity;->nativeAdd(II)I"))
    }
}
