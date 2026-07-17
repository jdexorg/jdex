package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import jadx.api.plugins.input.insns.Opcode

class ReturnValuePass : AnalysisPass {

    override fun run(method: DexMethod, result: DataflowResult): List<InsnAnnotation> {
        if (!result.complete) return emptyList()
        val desc = "${method.declClass}->${method.ref.shortId}"
        val out = ArrayList<InsnAnnotation>()
        for (i in method.insns.indices) {
            val insn = method.insns[i]
            if (insn.opcode != Opcode.RETURN || !result.isReachableOffset(insn.offset)) continue
            val v = result.regIn(i, insn.regs[0])
            if (usefulValue(v)) out += InsnAnnotation(desc, insn.offset, "returns ${renderValue(v, method.ref.returnType)}")
        }
        return out
    }

}
