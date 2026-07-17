package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.CallResolver
import io.github.nitanmarcel.jdex.exec.Interpreter
import io.github.nitanmarcel.jdex.exec.StubNotImplemented
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.runtime.DvmThrowable
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode

internal fun entryFrameRegs(method: DexMethod): Array<Any?> {
    val regs = arrayOfNulls<Any?>(method.registersCount)
    var r = method.registersCount - method.paramWords
    if (!method.isStatic) { regs[r] = UnknownVal(method.declClass); r++ }
    for (t in method.ref.argTypes) {
        regs[r] = UnknownVal(t); r += if (t == "J" || t == "D") 2 else 1
    }
    return regs
}

internal fun handlerEdgesOf(method: DexMethod): Map<Int, IntArray> {
    if (method.tries.isEmpty()) return emptyMap()
    val m = HashMap<Int, IntArray>()
    for (i in method.insns.indices) {
        val off = method.insns[i].offset
        val hs = method.tries.filter { off in it.start..it.end }.flatMap { it.handlers }.map { it.offset }.distinct()
        if (hs.isNotEmpty()) m[i] = hs.toIntArray()
    }
    return m
}

internal fun hostOnlyResolver(vm: Vm, interp: Interpreter): CallResolver = CallResolver { insn, frame ->
    val ref = insn.ref as MethodRef
    val op = insn.opcode
    val hasReceiver = op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE
    val (recv, args) = interp.gatherArgs(insn, frame, ref, hasReceiver)
    val v = try {
        if (hasReceiver) vm.hostExec.invokeInstance(ref, recv, args) else vm.hostExec.invokeStatic(ref, args)
    } catch (e: StubNotImplemented) {
        UnknownVal(ref.returnType)
    } catch (e: DvmThrowable) {
        UnknownVal(ref.returnType)
    }
    v to ref.returnType
}
