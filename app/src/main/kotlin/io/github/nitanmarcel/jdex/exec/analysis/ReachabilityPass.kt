package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DexMethod

class ReachabilityPass : AnalysisPass {

    override fun run(method: DexMethod, result: DataflowResult): List<InsnAnnotation> {
        if (!result.complete) return emptyList()
        val descriptor = "${method.declClass}->${method.ref.shortId}"
        val out = ArrayList<InsnAnnotation>()
        var i = 0
        while (i < method.insns.size) {
            if (result.reachable[i]) { i++; continue }
            out += InsnAnnotation(descriptor, method.insns[i].offset, "unreachable")
            while (i < method.insns.size && !result.reachable[i]) i++
        }
        return out
    }
}
