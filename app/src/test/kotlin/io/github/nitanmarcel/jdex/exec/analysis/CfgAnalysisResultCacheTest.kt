package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfgAnalysisResultCacheTest {

    @Test
    fun resultIsComputedOnceAndReused() {
        val src = Fixtures.sample()
        val cfg = CfgAnalysis(src)
        val m = src.method("LSummaryReuse;", "twice()I")!!
        val r1 = cfg.result(m)
        val r2 = cfg.result(m)
        assertNotNull(r1)
        assertSame(r1, r2)
    }

    @Test
    fun analyzeDerivesFactsFromCachedResult() {
        val src = Fixtures.opaque()
        val cfg = CfgAnalysis(src)
        val m = src.method("LOpaque;", "alwaysTrue(I)I")!!
        val f = cfg.analyze(m)
        assertNotNull(f)
        assertTrue(f!!.decidedBranches.isNotEmpty())
        assertSame(cfg.result(m), cfg.result(m))
    }
}
