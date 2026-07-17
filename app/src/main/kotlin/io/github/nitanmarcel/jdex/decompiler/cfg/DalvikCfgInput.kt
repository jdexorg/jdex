package io.github.nitanmarcel.jdex.decompiler.cfg

import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import jadx.api.plugins.input.insns.Opcode

object DalvikCfgInput {
    private val IF = setOf(
        Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
        Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
    )
    private val SWITCH = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)
    private val RETURN = setOf(Opcode.RETURN, Opcode.RETURN_VOID)
    private val SEPARATOR = setOf(Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT, Opcode.MOVE_EXCEPTION)

    fun toCfgInsns(method: DexMethod): List<CfgInsn> = method.insns.filter { it.opcode != Opcode.NOP }.map { ins ->
        when {
            ins.opcode in IF -> CfgInsn(ins.offset, CfgInsnKind.IF, listOf(ins.target))
            ins.opcode == Opcode.GOTO -> CfgInsn(ins.offset, CfgInsnKind.GOTO, listOf(ins.target))
            ins.opcode in SWITCH -> CfgInsn(ins.offset, CfgInsnKind.SWITCH, switchTargets(ins))
            ins.opcode in RETURN -> CfgInsn(ins.offset, CfgInsnKind.RETURN, emptyList())
            ins.opcode == Opcode.THROW -> CfgInsn(ins.offset, CfgInsnKind.THROW, emptyList())
            ins.opcode in SEPARATOR -> CfgInsn(ins.offset, CfgInsnKind.SEPARATOR, emptyList())
            ins.opcode.name.startsWith("MOVE_RESULT") -> CfgInsn(ins.offset, CfgInsnKind.MOVE_RESULT, emptyList())
            else -> CfgInsn(ins.offset, CfgInsnKind.NORMAL, emptyList())
        }
    }

    fun triesOf(method: DexMethod): List<TryRegion> =
        method.tries.map { t -> TryRegion(t.start, t.end, t.handlers.map { it.offset }) }

    private fun switchTargets(ins: DalvikInsn): List<Int> =
        (ins.payload as? SwitchPayload)?.targets?.map { ins.offset + it } ?: emptyList()
}
