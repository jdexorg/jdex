package io.github.nitanmarcel.jdex.decompiler.cfg

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class JdexCfgBuilderTest {
    private fun insn(off: Int, kind: CfgInsnKind = CfgInsnKind.NORMAL, vararg t: Int) =
        CfgInsn(off, kind, t.toList())

    @Test fun `trailing return is its own block (jadx separates it)`() {
        val cfg = JdexCfgBuilder.build(listOf(insn(0), insn(1), insn(2, CfgInsnKind.RETURN)))
        assertEquals(setOf(0, 2), cfg.blocks.keys)
        assertEquals(setOf(2), cfg.blocks.getValue(0).successors)
        assertEquals(emptySet<Int>(), cfg.blocks.getValue(2).successors)
    }

    @Test fun `if and return are isolated blocks with fallthrough and target`() {
        val cfg = JdexCfgBuilder.build(listOf(
            insn(0), insn(1, CfgInsnKind.IF, 4), insn(2), insn(3, CfgInsnKind.GOTO, 5),
            insn(4), insn(5, CfgInsnKind.RETURN)))
        assertEquals(setOf(0, 1, 2, 4, 5), cfg.blocks.keys)
        assertEquals(setOf(1), cfg.blocks.getValue(0).successors)
        assertEquals(setOf(2, 4), cfg.blocks.getValue(1).successors)
        assertEquals(setOf(5), cfg.blocks.getValue(2).successors)
        assertEquals(setOf(5), cfg.blocks.getValue(4).successors)
        assertEquals(setOf(1), cfg.blocks.getValue(2).predecessors)
    }

    @Test fun `switch has one successor per target plus fallthrough default`() {
        val cfg = JdexCfgBuilder.build(listOf(
            insn(0, CfgInsnKind.SWITCH, 2, 3), insn(1, CfgInsnKind.RETURN),
            insn(2, CfgInsnKind.RETURN), insn(3, CfgInsnKind.RETURN)))
        assertEquals(setOf(1, 2, 3), cfg.blocks.getValue(0).successors)
    }
}
