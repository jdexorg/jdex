package io.github.nitanmarcel.jdex.decompiler.cfg

import io.github.nitanmarcel.jdex.project.ApkSession
import jadx.core.dex.visitors.blocks.JdexCfgSpikeCapture
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue

class JadxCfgReferenceTest {
    @Test fun `capture pass records references for decompiled methods`() {
        val apk = File(JadxCfgReferenceTest::class.java.getResource("/fixtures/jdex-debug-target.apk")!!.toURI())
        val s = ApkSession.load(apk, "w")
        val refs = java.util.concurrent.ConcurrentHashMap<String, JadxCfgReference.Ref>()
        JdexCfgSpikeCapture.sink = { raw, sid, ref -> refs["$raw#$sid"] = ref }
        try {
            s.classRawNames().filter { !it.startsWith("android") }.take(60)
                .forEach { runCatching { s.decompile(it, ApkSession.DecompileMode.JDEC) } }
        } finally { JdexCfgSpikeCapture.sink = null }
        assertTrue(refs.isNotEmpty())
        assertTrue(refs.values.any { it.leaders.isNotEmpty() && it.edges.isNotEmpty() })
    }
}
