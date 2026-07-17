package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.AbsHeap
import io.github.nitanmarcel.jdex.exec.EngineContext
import io.github.nitanmarcel.jdex.exec.Frame
import io.github.nitanmarcel.jdex.exec.Interpreter
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import io.github.nitanmarcel.jdex.exec.runtime.WideHigh

class TraceFacts(
    val complete: Boolean,
    val takenTargets: Map<Int, Set<Int>>,
    val visitedOffsets: Set<Int>,
)

class PreciseTrace(private val vm: Vm) {

    private val interp = Interpreter(vm)

    private val heap = object : AbsHeap {
        override fun newInstance(site: String, type: String): Any? = UnknownVal(type)
        override fun iget(obj: Any?, key: String, type: String): Any? = UnknownVal(type)
        override fun iput(obj: Any?, key: String, v: Any?) {}
        override fun sget(declClass: String, key: String, type: String): Any? {
            vm.ensureClinit(declClass)
            val ctx = vm.ctx
            if (ctx != null && (key in ctx.mutableFields || type in EngineContext.MUTABLE_CONTAINER_TYPES)) return UnknownVal(type)
            return vm.staticsOf(declClass)[key] ?: UnknownVal(type)
        }
        override fun sput(declClass: String, key: String, v: Any?) {}
    }

    private val resolver = hostOnlyResolver(vm, interp)

    private class St(val pc: Int, val regs: Array<Any?>, val result: Any?, val resultType: String?)

    fun trace(method: DexMethod): TraceFacts {
        runCatching { vm.ensureClinit(method.declClass) }
        val insns = method.insns
        val handlerEdges = handlerEdgesOf(method)
        val visited = HashSet<String>()
        val takenTargets = HashMap<Int, MutableSet<Int>>()
        val visitedOffsets = HashSet<Int>()
        val wl = ArrayDeque<St>()
        wl.add(St(0, entryFrameRegs(method), null, null))
        var complete = true
        var steps = 0
        val cap = (insns.size + 4) * 256 + 4096

        while (wl.isNotEmpty()) {
            if (steps++ > cap || vm.deadlineExceeded()) { complete = false; break }
            val st = wl.removeFirst()
            if (!visited.add(keyOf(st.pc, st.regs, st.result, st.resultType))) continue
            val off = insns[st.pc].offset
            visitedOffsets.add(off)

            val frame = Frame(st.regs.size)
            System.arraycopy(st.regs, 0, frame.regs, 0, st.regs.size)
            frame.result = st.result
            frame.resultType = st.resultType
            val res = try { interp.absStep(method, insns[st.pc], frame, st.pc, resolver, heap) }
                catch (e: Throwable) { complete = false; break }

            if (res.successors.isNotEmpty()) {
                val set = takenTargets.getOrPut(off) { HashSet() }
                for (succOff in res.successors) set.add(succOff)
            }
            for (succOff in res.successors) {
                val si = method.offsetToIndex[succOff] ?: continue
                wl.add(St(si, frame.regs.copyOf(), frame.result, frame.resultType))
            }
            handlerEdges[st.pc]?.forEach { hOff ->
                val hi = method.offsetToIndex[hOff] ?: return@forEach
                wl.add(St(hi, st.regs.copyOf(), st.result, st.resultType))
            }
        }
        return TraceFacts(complete, takenTargets, visitedOffsets)
    }

    private fun keyOf(pc: Int, regs: Array<Any?>, result: Any?, resultType: String?): String {
        val sb = StringBuilder()
        sb.append(pc).append('|')
        for (v in regs) sb.append(canon(v)).append(',')
        sb.append('|').append(canon(result)).append('|').append(resultType)
        return sb.toString()
    }

    private fun canon(v: Any?): String = when (v) {
        null -> "n"
        is UnknownVal -> "U:" + (v.type ?: "?")
        is Boolean -> "b$v"
        is Int -> "i$v"
        is Long -> "l$v"
        is Char -> "c${v.code}"
        is Byte -> "y$v"
        is Short -> "h$v"
        is Float -> "f${v.toRawBits()}"
        is Double -> "d${v.toRawBits()}"
        is String -> "s$v"
        WideHigh -> "W"
        else -> "o" + System.identityHashCode(v)
    }
}
