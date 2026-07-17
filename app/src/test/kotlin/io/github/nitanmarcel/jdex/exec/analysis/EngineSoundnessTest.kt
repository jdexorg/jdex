package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.EngineContext
import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EngineSoundnessTest {

    private val src = Fixtures.degProbe()
    private fun facts(id: String) =
        CfgAnalysis(src, ctx = EngineContext(src)).analyze(src.method("LDeg;", id)!!)

    @Test
    fun containerFieldFlowingThroughHelperIsNotDecided() {
        assertNull(facts("viaHelper()I"))
    }

    @Test
    fun localContainerMutatedByUnresolvedCallIsNotDecided() {
        assertNull(facts("localMutated(LDeg\$Ext;)I"))
    }
}
