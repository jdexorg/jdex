package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import io.github.nitanmarcel.jdex.exec.model.DexMethod

class AnalysisRunner(
    private val source: MethodSource,
    private val passes: List<AnalysisPass>,
    private val limits: ExecLimits = ExecLimits(),
) {
    fun analyze(methods: List<DexMethod>): List<InsnAnnotation> {
        val out = ArrayList<InsnAnnotation>()
        for (m in methods) {
            val result = runCatching { Dataflow(Vm(source, limits = limits)).analyze(m) }.getOrNull() ?: continue
            for (p in passes) out += runCatching { p.run(m, result) }.getOrDefault(emptyList())
        }
        return out
    }
}
