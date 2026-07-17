package io.github.nitanmarcel.jdex.decompiler.cfg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class JdexCfgExceptionTest {
    @Test fun `separator instructions get their own block`() {
        val cfg = JdexCfgBuilder.build(listOf(
            CfgInsn(0, CfgInsnKind.NORMAL, emptyList()),
            CfgInsn(1, CfgInsnKind.SEPARATOR, emptyList()),
            CfgInsn(2, CfgInsnKind.NORMAL, emptyList()),
            CfgInsn(3, CfgInsnKind.RETURN, emptyList())))
        assertEquals(setOf(0, 1, 2, 3), cfg.blocks.keys)
        assertEquals(setOf(1), cfg.blocks.getValue(0).successors)
        assertEquals(setOf(2), cfg.blocks.getValue(1).successors)
    }

    @Test fun `try region boundaries and handler are leaders and covered blocks edge to the handler`() {
        val insns = listOf(
            CfgInsn(0, CfgInsnKind.NORMAL, emptyList()),
            CfgInsn(2, CfgInsnKind.NORMAL, emptyList()),
            CfgInsn(4, CfgInsnKind.NORMAL, emptyList()),
            CfgInsn(6, CfgInsnKind.SEPARATOR, emptyList()),
            CfgInsn(8, CfgInsnKind.RETURN, emptyList()))
        val tries = listOf(TryRegion(start = 2, end = 6, handlers = listOf(6)))
        val cfg = JdexCfgBuilder.build(insns, tries)
        assertTrue(cfg.blocks.containsKey(2), "try start is a leader")
        assertTrue(cfg.blocks.containsKey(6), "handler is a leader")
        assertTrue(6 in cfg.blocks.getValue(2).successors, "covered block edges to handler")
        assertTrue(6 in cfg.reachable())
    }
}
