package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.StringDecryptor
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecValuePassTest {

    private fun decompileSample(withPass: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/sample.dex")!!.readBytes()
        val dex = File.createTempFile("jdecpass", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val jadx = JadxDecompiler(JadxArgs().apply {
            inputFiles.add(dex)
            setShowInconsistentCode(true)
            renameFlags = emptySet()
        })
        if (withPass) {
            val src = Fixtures.sample()
            val anns = StringDecryptor(src).recoverAll(src.allMethods())
            jadx.addCustomPass(JdecValuePass { anns })
        }
        jadx.load()
        return jadx.classes.first { it.fullName == "Sample" }.code
    }

    @Test
    fun rewritesDecryptorCallToTheConstantString() {
        val normal = decompileSample(withPass = false)
        val jdec = decompileSample(withPass = true)

        assertTrue(normal.contains("return dec(")) { normal }
        assertTrue(normal.contains("return greet(")) { normal }

        assertFalse(jdec.contains("return dec(")) { "decryptor call should be gone:\n$jdec" }
        assertTrue(jdec.contains("return \"hi\";")) { "secret() should return the literal:\n$jdec" }
        assertTrue(jdec.contains("return greet(")) { "pure concatenation is not a decryptor and must stay a call:\n$jdec" }
        assertFalse(jdec.contains("return \"hi x\";")) { "tag() must not fold a non-decryptor call to a literal:\n$jdec" }

        assertTrue(normal.substringAfter("String secret()").substringBefore("}").contains("byte[]")) { normal }
        assertFalse(jdec.substringAfter("String secret()").substringBefore("}").contains("byte[]")) {
            "dead byte[] producer should be suppressed in secret():\n$jdec"
        }
    }
}
