package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.DvmMethodHandle
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import jadx.api.plugins.input.insns.Opcode

class UnreflectTarget(
    val ref: MethodRef,
    val hasReceiver: Boolean,
    val opcode: Opcode,
    val operandToElement: IntArray,
)

class Unreflect(private val source: MethodSource) {
    private val vm = Vm(source)
    private val lists = HashMap<String, List<DexMethod?>?>()

    fun resolve(dispatcherDesc: String, index: Int): UnreflectTarget? {
        val wrapper = handles(dispatcherDesc)?.getOrNull(index) ?: return null
        return unwrap(wrapper)
    }

    private fun handles(dispatcher: String): List<DexMethod?>? = lists.getOrPut(dispatcher) {
        runCatching { vm.ensureClinit(dispatcher) }
        (vm.staticsOf(dispatcher).values.firstOrNull { it is List<*> && it.isNotEmpty() && it.all { e -> e is DvmMethodHandle } } as? List<*>)
            ?.map { (it as? DvmMethodHandle)?.dexMethod }
    }

    private fun unwrap(wrapper: DexMethod): UnreflectTarget? {
        if (!wrapper.isStatic) return null
        val inv = wrapper.insns.singleOrNull { it.opcode in INVOKE_OPCODES } ?: return null
        val ref = inv.ref as? MethodRef ?: return null
        val hasReceiver = inv.opcode != Opcode.INVOKE_STATIC && inv.opcode != Opcode.INVOKE_STATIC_RANGE

        val paramIndexByReg = HashMap<Int, Int>()
        var reg = wrapper.registersCount - wrapper.paramWords
        wrapper.ref.argTypes.forEachIndexed { j, t ->
            paramIndexByReg[reg] = j
            reg += if (t == "J" || t == "D") 2 else 1
        }

        val operandRegs = ArrayList<Int>(ref.argTypes.size + 1)
        var i = 0
        if (hasReceiver) operandRegs.add(inv.regs.getOrNull(i++) ?: return null)
        for (t in ref.argTypes) {
            operandRegs.add(inv.regs.getOrNull(i) ?: return null)
            i += if (t == "J" || t == "D") 2 else 1
        }
        val operandToElement = IntArray(operandRegs.size) { paramIndexByReg[operandRegs[it]] ?: return null }
        return UnreflectTarget(ref, hasReceiver, inv.opcode, operandToElement)
    }
}
