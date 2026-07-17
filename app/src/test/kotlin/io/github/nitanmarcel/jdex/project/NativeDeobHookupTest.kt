package io.github.nitanmarcel.jdex.project

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeDeobHookupTest {

    private fun res(name: String) = NativeDeobHookupTest::class.java.getResourceAsStream(name)!!.readBytes()

    @Test
    fun recoversNativeStringThroughApkSession(@TempDir dir: File) {
        val apk = File(dir, "native-crypto.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write(res("/jni/jnicrypt-caller.dex")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libjnicrypt.so")); zip.write(res("/jni/libjnicrypt_arm64-v8a.so")); zip.closeEntry()
        }
        ApkSession.load(apk, "native-crypto").use { session ->
            val all = session.classRawNames().flatMap { session.deobStringsForClass(it) }
            assertTrue(all.any { it.text == "\"hello\"" }) {
                "expected the native decryptor's plaintext, got: ${all.map { it.text }}"
            }
        }
    }
}
