package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecConcatCastPassTest {

    private fun body(withPass: Boolean): String {
        val bytes = Fixtures::class.java.getResourceAsStream("/fixtures/charconcat.dex")!!.readBytes()
        val dex = File.createTempFile("charconcat", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); setShowInconsistentCode(true) })
        if (withPass) d.addCustomPass(JdecConcatCastPass())
        d.load()
        return d.classesWithInners.first { it.rawName == "CharConcat" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun preservesMeaningfulCharCastInStringConcat() {
        val base = body(false)
        val jdec = body(true)
        assertTrue(base.contains("(char)")) { "control: jadx renders a (char) cast for append(char) of an int:\n$base" }
        assertTrue(jdec.contains("(char)")) { "the pass must NOT strip a meaningful (char) cast (only int-widening noise):\n$jdec" }
    }
}
