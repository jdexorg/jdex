package io.github.nitanmarcel.jdex.project

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class JdecConstFoldPassTest {

    @Test
    fun foldsConstantOpaquePredicatesAndRemovesDeadArith() {
        val apk = File.createTempFile("jdex-constfold", ".apk").apply {
            deleteOnExit()
            writeBytes(JdecConstFoldPassTest::class.java.getResourceAsStream("/fixtures/obfuscapk-cfgdemo.apk")!!.readBytes())
        }
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(apk); setSkipResources(true); setShowInconsistentCode(true) })
        d.addCustomPass(JdecBlockLockPass())
        d.addCustomPass(JdecConstFoldPass())
        d.load()
        val code = d.classesWithInners.first { it.rawName == "com.jdex.cfgdemo.MainActivity" }.topParentClass.codeInfo.codeStr
        assertFalse(code.contains("JdexConstFold")) { "the pass must not error:\n$code" }
        assertFalse(Regex("\\(\\d+ \\+ \\d+\\) % \\d+").containsMatchIn(code)) { "opaque arithmetic junk must be folded away:\n$code" }
        assertFalse(code.contains("Not found block with instruction")) {
            "cascade must not re-remove a const whose block is already gone (no stray DONT_GENERATE/warn):\n$code"
        }
    }
}
