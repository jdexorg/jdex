package io.github.nitanmarcel.jdex.decompiler.cfg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class JdexCfgPrunerTest {
    @Test fun `decided branch drops the not-taken edge and its now-unreachable block`() {
        val cfg = JdexCfgBuilder.build(listOf(
            CfgInsn(0, CfgInsnKind.IF, listOf(4)),
            CfgInsn(2, CfgInsnKind.RETURN, emptyList()),
            CfgInsn(4, CfgInsnKind.RETURN, emptyList())))
        val pruned = JdexCfgPruner.prune(cfg, decidedBranches = mapOf(0 to 4), deadOffsets = emptySet())
        assertEquals(setOf(4), pruned.blocks.getValue(0).successors)
        assertEquals(setOf(0, 4), pruned.blocks.keys)
        assertEquals(emptySet<Int>(), pruned.unreachable())
    }

    @Test fun `dead offset block is removed`() {
        val cfg = JdexCfgBuilder.build(listOf(
            CfgInsn(0, CfgInsnKind.GOTO, listOf(4)),
            CfgInsn(2, CfgInsnKind.RETURN, emptyList()),
            CfgInsn(4, CfgInsnKind.RETURN, emptyList())))
        val pruned = JdexCfgPruner.prune(cfg, emptyMap(), deadOffsets = setOf(2))
        assertEquals(setOf(0, 4), pruned.blocks.keys)
        assertEquals(emptySet<Int>(), pruned.unreachable())
    }
}
