package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod

interface ExecHook {
    fun onEnter(method: DexMethod, frame: Frame) {}
    fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int)
    fun onExit(method: DexMethod) {}
}
