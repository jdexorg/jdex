package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ValueOverlayTest {

    private fun session(dir: File): ApkSession {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/sample.dex")!!.readBytes()
        val dex = File(dir, "sample.dex").apply { writeBytes(bytes) }
        return ApkSession.load(dex, "jdectest")
    }

    @Test
    fun overlaysNonStringConstantsAndReturns(@TempDir dir: File) {
        val anns = session(dir).valueOverlayFor("Sample")
        assertTrue(anns.any { it.text == "= 5" }) { anns.map { it.text }.toString() }
        assertTrue(anns.any { it.text == "returns 5" }) { anns.map { it.text }.toString() }
    }

    @Test
    fun excludesStringValues(@TempDir dir: File) {
        val anns = session(dir).valueOverlayFor("Sample")
        assertTrue(anns.none { it.text.contains("\"") }) { anns.map { it.text }.toString() }
    }

    @Test
    fun jdecOutputCarriesValueComments(@TempDir dir: File) {
        val code = session(dir).decompile("Sample", ApkSession.DecompileMode.JDEC)!!.code
        assertTrue(code.contains("// jdex: = 5")) { code }
    }


    @Test
    fun javaModeIsUnaffected(@TempDir dir: File) {
        val code = session(dir).decompile("Sample", ApkSession.DecompileMode.JAVA)!!.code
        assertTrue(!code.contains("// jdex:")) { code }
    }

    @Test
    fun prewarmIsSafeAndDecompileStillWorks(@TempDir dir: File) {
        val s = session(dir)
        s.prewarm()
        val code = s.decompile("Sample", ApkSession.DecompileMode.JDEC)!!.code
        assertTrue(code.contains("// jdex: = 5")) { code }
    }
}
