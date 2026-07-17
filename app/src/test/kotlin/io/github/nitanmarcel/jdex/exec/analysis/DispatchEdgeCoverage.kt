package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.ExecHook
import io.github.nitanmarcel.jdex.exec.Frame
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import org.junit.jupiter.api.Assertions.assertTrue

internal fun assertRecoveredEdgesCoverRealPaths(
    src: MethodSource,
    method: DexMethod,
    g: DispatchGraph,
    params: List<Int>,
    minChecked: Int,
) {
    val nodeSet = method.insns.filter { it.payload is SwitchPayload }
        .flatMap { s -> (s.payload as SwitchPayload).targets.map { s.offset + it } }.toHashSet() + method.insns[0].offset

    var checked = 0
    for (p in params) {
        val seq = ArrayList<Int>()
        val hook = object : ExecHook {
            override fun onStep(m: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
                if (m === method && insn.offset in nodeSet && seq.lastOrNull() != insn.offset) seq.add(insn.offset)
            }
        }
        Vm(src, hook = hook).invoke(method, listOf(p))
        for (i in 0 until seq.size - 1) {
            checked++
            assertTrue(seq[i + 1] in g.edges[seq[i]].orEmpty()) {
                "real transition %04x->%04x (param %d) missing from recovered CFG".format(seq[i], seq[i + 1], p)
            }
        }
    }
    assertTrue(checked > minChecked) { "expected many transitions exercised, got $checked" }
}
