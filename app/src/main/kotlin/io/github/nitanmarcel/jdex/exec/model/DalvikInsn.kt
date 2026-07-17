package io.github.nitanmarcel.jdex.exec.model

import jadx.api.plugins.input.insns.Opcode

sealed interface InsnPayload

class SwitchPayload(val keys: IntArray, val targets: IntArray) : InsnPayload

class ArrayPayload(val size: Int, val elementSize: Int, val data: Any?) : InsnPayload

class DalvikInsn(
    val opcode: Opcode,
    val offset: Int,
    val regs: IntArray,
    val literal: Long,
    val target: Int,
    val ref: InsnRef?,
    var payload: InsnPayload?,
)
