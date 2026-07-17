package io.github.nitanmarcel.jdex.decompiler.cfg

import io.github.nitanmarcel.jdex.exec.EngineContext
import io.github.nitanmarcel.jdex.exec.analysis.CfgAnalysis
import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue

class OpaquePruneTest {
    private fun source(): DexInputSource {
        val bytes = OpaquePruneTest::class.java.getResourceAsStream("/fixtures/opaque.dex")!!.readBytes()
        val dex = File.createTempFile("opaque", ".dex").apply { deleteOnExit(); writeBytes(bytes) }
        return DexInputSource.load(dex)
    }

    @Test fun `emulator facts prune at least one opaque branch and never leave unreachable blocks`() {
        val src = source()
        val cfg = CfgAnalysis(src, ctx = EngineContext(src))
        var prunedSomewhere = false
        for (m in src.allMethods().filter { it.insns.size > 1 }) {
            val facts = cfg.analyze(m) ?: continue
            val ours = JdexCfgBuilder.build(DalvikCfgInput.toCfgInsns(m))
            val pruned = JdexCfgPruner.prune(ours, facts.decidedBranches, facts.deadOffsets)
            assertTrue(pruned.unreachable().isEmpty(), "unreachable left in $m")
            if (facts.decidedBranches.isNotEmpty() && pruned.blocks.size < ours.blocks.size) prunedSomewhere = true
        }
        assertTrue(prunedSomewhere, "expected the opaque fixture to have at least one decided branch that removed a block")
    }

    @Test fun `with no facts the CFG is unchanged (soundness - only prune on proof)`() {
        val src = source()
        for (m in src.allMethods().filter { it.insns.size > 1 }) {
            val ours = JdexCfgBuilder.build(DalvikCfgInput.toCfgInsns(m))
            val pruned = JdexCfgPruner.prune(ours, emptyMap(), emptySet())
            assertTrue(pruned.blocks.keys == ours.blocks.keys)
        }
    }
}
