package io.github.nitanmarcel.jdex.decompiler.cfg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class JdexCfgReachabilityTest {
    @Test fun `orphan block is unreachable`() {
        val cfg = JdexCfgBuilder.build(listOf(
            CfgInsn(0, CfgInsnKind.RETURN, emptyList()),
            CfgInsn(2, CfgInsnKind.RETURN, emptyList())))
        assertEquals(setOf(0), cfg.reachable())
        assertEquals(setOf(2), cfg.unreachable())
    }

    @Test fun `all blocks reachable when linked`() {
        val cfg = JdexCfgBuilder.build(listOf(
            CfgInsn(0, CfgInsnKind.GOTO, listOf(1)),
            CfgInsn(1, CfgInsnKind.RETURN, emptyList())))
        assertEquals(setOf(0, 1), cfg.reachable())
        assertEquals(emptySet<Int>(), cfg.unreachable())
    }
}
