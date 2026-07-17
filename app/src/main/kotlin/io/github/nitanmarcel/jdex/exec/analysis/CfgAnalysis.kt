package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import jadx.api.plugins.input.insns.Opcode
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class MethodCfgFacts(
    val decidedBranches: Map<Int, Int>,
    val deadOffsets: Set<Int>,
    val foldedReads: Set<Int> = emptySet(),
    val foldedCalls: Set<Int> = emptySet(),
)

class CfgAnalysis(
    private val source: MethodSource,
    private val limits: ExecLimits = ExecLimits(maxMillis = 2000),
    private val ctx: io.github.nitanmarcel.jdex.exec.EngineContext? = null,
) {
    private val resultCache = ConcurrentHashMap<String, Optional<DataflowResult>>()

    fun result(method: DexMethod): DataflowResult? {
        if (method.insns.size > MAX_INSNS) return null
        return resultCache.computeIfAbsent("${method.declClass}->${method.ref.shortId}") {
            Optional.ofNullable(runCatching { Dataflow(Vm(source, limits = limits, ctx = ctx)).analyze(method) }.getOrNull())
        }.orElse(null)
    }

    fun analyze(method: DexMethod): MethodCfgFacts? {
        if (method.insns.size > MAX_INSNS) return null
        if (method.insns.none { it.opcode in IF_OPS || it.opcode in SWITCH_OPS }) return null
        val r = result(method) ?: return null
        if (!r.complete) return null
        val branches = LinkedHashMap<Int, Int>()
        for (insn in method.insns) {
            if (insn.opcode !in IF_OPS && insn.opcode !in SWITCH_OPS) continue
            val succ = r.successorsOf(insn.offset)
            if (succ.size == 1) branches[insn.offset] = skipNops(method, succ[0])
        }
        val dead = LinkedHashSet<Int>()
        for (i in method.insns.indices) if (!r.reachable[i]) dead.add(method.insns[i].offset)
        val folded = LinkedHashSet<Int>()
        for (insn in method.insns) {
            val nm = insn.opcode.name
            if ((nm.startsWith("AGET") || nm.startsWith("SGET") || nm.startsWith("IGET")) && insn.regs.isNotEmpty()) {
                val v = r.regOutAtOffset(insn.offset, insn.regs[0])
                if (v != null && v !is io.github.nitanmarcel.jdex.exec.runtime.UnknownVal) folded.add(insn.offset)
            }
        }
        val calls = LinkedHashSet<Int>()
        for (insn in method.insns) {
            val nm = insn.opcode.name
            if (!nm.startsWith("INVOKE") || "STATIC" in nm || "CUSTOM" in nm || "POLYMORPHIC" in nm) continue
            val mref = insn.ref as? io.github.nitanmarcel.jdex.exec.model.MethodRef ?: continue
            if ("${mref.declClass}->${mref.name}" !in io.github.nitanmarcel.jdex.exec.HostBoundary.PURE_ACCESSORS) continue
            if (insn.regs.isEmpty()) continue
            val recv = r.regInAtOffset(insn.offset, insn.regs[0])
            if (recv != null && recv !is io.github.nitanmarcel.jdex.exec.runtime.UnknownVal) calls.add(insn.offset)
        }
        refineWithTrace(method, r, branches, dead)
        return if (branches.isEmpty() && dead.isEmpty() && folded.isEmpty() && calls.isEmpty()) null
        else MethodCfgFacts(branches, dead, folded, calls)
    }

    private fun refineWithTrace(
        method: DexMethod,
        r: DataflowResult,
        branches: LinkedHashMap<Int, Int>,
        dead: LinkedHashSet<Int>,
    ) {
        val hasUndecided = method.insns.any {
            (it.opcode in IF_OPS || it.opcode in SWITCH_OPS) && r.isReachableOffset(it.offset) && it.offset !in branches
        }
        if (!hasUndecided) return
        val trace = runCatching {
            io.github.nitanmarcel.jdex.exec.graph.PreciseTrace(Vm(source, limits = limits, ctx = ctx)).trace(method)
        }.getOrNull() ?: return
        if (!trace.complete) return
        for ((off, targets) in trace.takenTargets) {
            if (targets.size != 1) continue
            if (off in branches) continue
            val idx = method.offsetToIndex[off] ?: continue
            val op = method.insns[idx].opcode
            if (op !in IF_OPS && op !in SWITCH_OPS) continue
            branches[off] = skipNops(method, targets.first())
        }
        for (i in method.insns.indices) {
            if (method.insns[i].offset !in trace.visitedOffsets) dead.add(method.insns[i].offset)
        }
    }

    private fun skipNops(method: DexMethod, offset: Int): Int {
        var idx = method.offsetToIndex[offset] ?: return offset
        while (idx < method.insns.size && method.insns[idx].opcode == Opcode.NOP) idx++
        return method.insns.getOrNull(idx)?.offset ?: offset
    }

    fun analyzeAll(methods: List<DexMethod>): Map<String, MethodCfgFacts> {
        val out = LinkedHashMap<String, MethodCfgFacts>()
        for (m in methods) runCatching { analyze(m) }.getOrNull()?.let {
            out["${m.declClass}->${m.ref.shortId}"] = it
        }
        return out
    }

    companion object {
        private const val MAX_INSNS = 6000
        private val IF_OPS = setOf(
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        )
        private val SWITCH_OPS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)
    }
}
