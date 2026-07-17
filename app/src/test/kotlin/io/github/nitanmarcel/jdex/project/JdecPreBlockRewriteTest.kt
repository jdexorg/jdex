package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.analysis.StringDecryptor
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecPreBlockRewriteTest {
    private fun dexFile(): File {
        val bytes = javaClass.getResourceAsStream("/fixtures/sample.dex")!!.readBytes()
        return File.createTempFile("preblock", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
    }

    private fun decompileSample(withFacts: Boolean): String {
        val dex = dexFile()
        val d = JadxDecompiler(JadxArgs().apply { inputFiles.add(dex); setSkipResources(true); renameFlags = emptySet() })
        if (withFacts) {
            val src = DexInputSource.load(dex)
            val sd = StringDecryptor(src)
            d.addCustomPass(JdecPreBlockRewrite(sites = { raw, shortId ->
                val m = src.method("L${raw.replace('.', '/')};", shortId)
                if (m == null) emptyList() else sd.recoverSites(m)
            }))
        } else {
            d.addCustomPass(JdecPreBlockRewrite(sites = { _, _ -> emptyList() }))
        }
        d.load()
        return d.classesWithInners.first { it.rawName == "Sample" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun noFactsLeavesDecompileWorking() {
        val code = decompileSample(withFacts = false)
        assertTrue(code.contains("class Sample")) { code }
        assertTrue(code.contains("return dec(")) { "control: decryptor call present without facts:\n$code" }
    }

    @Test
    fun rewritesDecryptorCallToConstStringPreBlock() {
        val normal = decompileSample(withFacts = false)
        val jdec = decompileSample(withFacts = true)

        assertTrue(normal.contains("return greet(")) { normal }

        assertFalse(jdec.contains("return dec(")) { "decryptor call must be gone:\n$jdec" }
        assertTrue(jdec.contains("return \"hi\";")) { "secret() must return the inlined literal:\n$jdec" }
        assertTrue(jdec.contains("return greet(")) { "pure concatenation is not a decryptor and must stay a call:\n$jdec" }
        assertFalse(jdec.contains("return \"hi x\";")) { "non-decryptor tag() must not fold to a literal:\n$jdec" }
        val secretBody = jdec.substringAfter("String secret()").substringBefore("}")
        assertFalse(secretBody.contains("byte[]")) { "engine-reported dead byte[] producer must be removed, not left orphaned:\n$secretBody" }
    }
}
