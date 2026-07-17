package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.StringDecryptor
import io.github.nitanmarcel.jdex.exec.analysis.Unreflect
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecUnreflectPassTest {

    @Test
    fun rewritesReflectionIndirectionsToDirectCalls() {
        val src = Fixtures.obfuscapk()
        val apk = File.createTempFile("jdex-unreflect", ".apk").apply {
            deleteOnExit()
            writeBytes(JdecUnreflectPassTest::class.java.getResourceAsStream("/fixtures/obfuscapk-cfgdemo.apk")!!.readBytes())
        }
        val deob = StringDecryptor(src).recoverAll(src.allMethods())
        val ur = Unreflect(src)
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(apk); setSkipResources(true); setShowInconsistentCode(true) })
        d.addCustomPass(JdecValuePass { deob })
        d.addCustomPass(JdecUnreflectPass(resolve = { raw, idx -> ur.resolve("L${raw.replace('.', '/')};", idx) }))
        d.addCustomPass(JdecConcatCastPass())
        d.load()
        val code = d.classesWithInners.first { it.rawName == "com.jdex.cfgdemo.MainActivity" }
            .topParentClass.codeInfo.codeStr
        val onCreate = code.substringAfter("void onCreate").substringBefore("\n    static ")
        assertFalse(onCreate.contains("ApiReflection.obfuscate")) { "all reflection indirections must be rewritten:\n$onCreate" }
        assertTrue(onCreate.contains("super.onCreate(")) { "super.onCreate must be recovered" }
        assertTrue(onCreate.contains("Log.i(")) { "Log.i calls must be recovered" }
        assertFalse(onCreate.contains("Integer.valueOf")) { "primitive box/unbox dance must be unwrapped:\n$onCreate" }
        assertFalse(onCreate.contains(".intValue()")) { "box/cast/unbox round-trip must be collapsed:\n$onCreate" }
        assertTrue(onCreate.contains("\"collatz(27)=\" + collatzSteps(27)")) { "int-returning call must stay visible in a clean concat:\n$onCreate" }
        assertFalse(onCreate.contains("new StringBuilder")) { "no dead StringBuilder chain may survive a folded toString:\n$onCreate" }
        assertTrue(onCreate.contains("classify(150)")) { "app-method call must stay visible, not folded to its value:\n$onCreate" }
        assertTrue(onCreate.contains("\"classify(150)=\"")) { "the decrypted prefix must still be folded:\n$onCreate" }
        assertFalse(Regex("\\(int\\) \\w+\\(").containsMatchIn(onCreate)) { "no spurious (int) cast on a concat call operand:\n$onCreate" }
        assertTrue(onCreate.contains("\"classify(150)=\" + classify(150)")) { "String-returning call appends without an invalid (int) cast:\n$onCreate" }
        assertTrue(onCreate.contains("\"grade(85)=\" + grade(85)")) { "char-returning call appends as char, no (int):\n$onCreate" }
        val sbTypeWarns = Regex("Type inference failed for: r\\d+v\\d+, types: \\[java\\.lang\\.StringBuilder\\]").findAll(code).count()
        assertTrue(sbTypeWarns < 14) { "receiver type-pin must cut StringBuilder type-inference warnings below the un-pinned baseline (14); got $sbTypeWarns" }
    }
}
