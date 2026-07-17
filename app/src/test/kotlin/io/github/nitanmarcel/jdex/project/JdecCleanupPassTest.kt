package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.CfgAnalysis
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class JdecCleanupPassTest {

    private fun dexFile(): File {
        val bytes = javaClass.getResourceAsStream("/fixtures/opaque.dex")!!.readBytes()
        return File.createTempFile("jdex-opaque", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
    }

    private fun decompileOpaque(withPass: Boolean): String {
        val dex = dexFile()
        val src = DexInputSource.load(dex)
        val facts = CfgAnalysis(src).analyzeAll(src.allMethods())
        val d = JadxDecompiler(JadxArgs().apply {
            inputFiles.add(dex)
            setSkipResources(true)
        })
        if (withPass) d.addCustomPass(JdecCleanupPass { rawName, shortId -> facts["L${rawName.replace('.', '/')};->$shortId"] })
        d.load()
        return d.classesWithInners.first { it.rawName == "Opaque" }.topParentClass.codeInfo.codeStr
    }

    @Test
    fun foldsStaticallyDecidedBranchesAwayDeadCode() {
        assertTrue("999" in decompileOpaque(withPass = false)) {
            "control: jadx alone keeps the opaque-predicate dead code"
        }
        assertFalse("999" in decompileOpaque(withPass = true)) {
            "the cleanup pass should fold both decided branches, dropping the unreachable 999"
        }
    }

    @Test
    fun doesNotFoldBranchesOnAndroidVersion() {
        assertTrue("1000" in decompileOpaque(withPass = false)) {
            "control: jadx alone keeps the API<=28 backport body"
        }
        assertTrue("1000" in decompileOpaque(withPass = true)) {
            "Build.VERSION.SDK_INT is device-dependent; the version-guarded branch must NOT be folded, so the backport body survives"
        }
    }
}
