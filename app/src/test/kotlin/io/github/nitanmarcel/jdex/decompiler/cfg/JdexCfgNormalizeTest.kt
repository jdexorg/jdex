package io.github.nitanmarcel.jdex.decompiler.cfg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class JdexCfgNormalizeTest {
    @Test fun `if-goto fold redirects the if false-edge to the goto target and drops the goto block`() {
        val insns = listOf(
            CfgInsn(0, CfgInsnKind.IF, listOf(5)),
            CfgInsn(1, CfgInsnKind.GOTO, listOf(7)),
            CfgInsn(5, CfgInsnKind.RETURN, emptyList()),
            CfgInsn(7, CfgInsnKind.RETURN, emptyList()))
        val cfg = JdexCfgBuilder.build(insns)
        val folded = JdexCfgNormalize.foldGotoBlocks(cfg, insns.associateBy { it.offset })
        assertEquals(setOf(0, 5, 7), folded.blocks.keys)
        assertEquals(setOf(5, 7), folded.blocks.getValue(0).successors)
        assertEquals(setOf(0), folded.blocks.getValue(7).predecessors)
    }

    @Test fun `a shared goto with two predecessors is not folded`() {
        val insns = listOf(
            CfgInsn(0, CfgInsnKind.IF, listOf(4)),
            CfgInsn(2, CfgInsnKind.IF, listOf(4)),
            CfgInsn(3, CfgInsnKind.RETURN, emptyList()),
            CfgInsn(4, CfgInsnKind.GOTO, listOf(6)),
            CfgInsn(6, CfgInsnKind.RETURN, emptyList()))
        val cfg = JdexCfgBuilder.build(insns)
        val folded = JdexCfgNormalize.foldGotoBlocks(cfg, insns.associateBy { it.offset })
        assertEquals(true, folded.blocks.containsKey(4))
    }
}
