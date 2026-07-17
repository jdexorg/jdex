package io.github.nitanmarcel.jdex.decompiler.cfg

import io.github.nitanmarcel.jdex.project.ApkSession
import jadx.core.dex.visitors.blocks.JdexCfgSpikeCapture
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue

class CfgParitySmokeTest {
    private val excOps = setOf("MONITOR_ENTER", "MONITOR_EXIT", "MOVE_EXCEPTION")

    @Test fun `our CFG matches jadx raw split on the bundled apk`() {
        val apk = File(CfgParitySmokeTest::class.java.getResource("/fixtures/jdex-debug-target.apk")!!.toURI())
        val s = ApkSession.load(apk, "w")
        val refs = java.util.concurrent.ConcurrentHashMap<String, JadxCfgReference.Ref>()
        JdexCfgSpikeCapture.sink = { raw, sid, ref -> refs["$raw#$sid"] = ref }
        val pkg = s.appPackage
        val main = if (pkg != null) s.classRawNames().filter { it == pkg || it.startsWith("$pkg.") }
            else s.classRawNames().filter { c -> listOf("android.", "androidx.", "java.", "kotlin.").none { c.startsWith(it) } }
        try {
            main.forEach { runCatching { s.decompile(it, ApkSession.DecompileMode.JDEC) } }
        } finally { JdexCfgSpikeCapture.sink = null }

        val src = s.engineSource()!!
        var all = 0; var leaderOk = 0; var nonExc = 0; var fullOk = 0
        for ((key, ref) in refs) {
            val raw = key.substringBefore('#'); val sid = key.substringAfter('#')
            val m = src.method("L" + raw.replace('.', '/') + ";", sid) ?: continue
            if (m.insns.isEmpty()) continue
            val cfgInsns = DalvikCfgInput.toCfgInsns(m)
            val ours = JdexCfgNormalize.foldGotoBlocks(
                JdexCfgBuilder.build(cfgInsns, DalvikCfgInput.triesOf(m)), cfgInsns.associateBy { it.offset })
            all++
            if (ref.leaders == ours.blocks.keys) leaderOk++
            if (!ref.hasExceptionHandlers && m.insns.none { it.opcode.name in excOps }) {
                nonExc++
                if (ref.leaders == ours.blocks.keys && ref.leaders.all { ref.edges[it] == ours.blocks[it]?.successors }) fullOk++
            }
        }
        println("leaderParity(all)=$leaderOk/$all fullParity(non-exc)=$fullOk/$nonExc")
        assertTrue(all > 0 && nonExc > 0)
        assertTrue(leaderOk.toDouble() / all >= 0.95, "all-method leader parity $leaderOk/$all below 95%")
        assertTrue(fullOk.toDouble() / nonExc >= 0.95, "non-exc full parity $fullOk/$nonExc below 95%")
    }
}
