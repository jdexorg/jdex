package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import jadx.api.plugins.input.insns.Opcode

class ValueResolutionPass : AnalysisPass {

    override fun run(method: DexMethod, result: DataflowResult): List<InsnAnnotation> {
        val descriptor = "${method.declClass}->${method.ref.shortId}"
        val out = ArrayList<InsnAnnotation>()
        val insns = method.insns
        for (i in insns.indices) {
            if (insns[i].opcode !in INVOKE_OPCODES) continue
            val next = insns.getOrNull(i + 1) ?: continue
            if (next.opcode != Opcode.MOVE_RESULT) continue
            val v = result.regOut(i + 1, next.regs[0])
            val returnType = (insns[i].ref as? io.github.nitanmarcel.jdex.exec.model.MethodRef)?.returnType
            if (usefulValue(v)) out += InsnAnnotation(descriptor, insns[i].offset, renderValue(v, returnType))
        }
        return out
    }

}
