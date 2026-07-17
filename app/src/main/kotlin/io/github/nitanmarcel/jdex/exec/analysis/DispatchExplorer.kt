package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.AbsHeap
import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.Frame
import io.github.nitanmarcel.jdex.exec.Interpreter
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.graph.entryFrameRegs
import io.github.nitanmarcel.jdex.exec.graph.hostOnlyResolver
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode

class DispatchGraph(
    val entry: Int,
    val nodes: Set<Int>,
    val edges: Map<Int, Set<Int>>,
    val terminals: Set<Int>,
    val complete: Boolean,
    val strTarget: Map<Int, Int>,
)

class DispatchExplorer(
    private val source: MethodSource,
    private val limits: ExecLimits = ExecLimits(maxMillis = 4000),
) {
    private val vm = Vm(source, limits = limits)
    private val interp = Interpreter(vm)

    private class St(val pc: Int, val regs: Array<Any?>, val result: Any?, val resultType: String?, val lastNode: Int, val lastStr: Int)

    private val heap = object : AbsHeap {
        override fun newInstance(site: String, type: String): Any? = UnknownVal(type)
        override fun iget(obj: Any?, key: String, type: String): Any? = UnknownVal(type)
        override fun iput(obj: Any?, key: String, v: Any?) {}
        override fun sget(declClass: String, key: String, type: String): Any? = UnknownVal(type)
        override fun sput(declClass: String, key: String, v: Any?) {}
    }

    private val resolver = hostOnlyResolver(vm, interp)

    fun explore(method: DexMethod): DispatchGraph? {
        val state = dispatchStateOf(method) ?: return null
        val dispatchReg = state.reg
        runCatching { vm.ensureClinit(method.declClass) }
        val insns = method.insns
        val switchTargets = insns.filter { it.payload is SwitchPayload }
            .flatMap { s -> (s.payload as SwitchPayload).targets.map { s.offset + it } }.toHashSet()
        val entryOffset = insns[0].offset
        val handlerOffsets = method.tries.flatMap { it.handlers }.map { it.offset }.distinct()
        val nodeSet = switchTargets + entryOffset + handlerOffsets

        val edges = HashMap<Int, MutableSet<Int>>()
        val terminals = HashSet<Int>()
        val strTarget = HashMap<Int, Int>()
        val visited = HashSet<Long>()
        val nodeRegs = HashMap<Int, Array<Any?>>()
        val wl = ArrayDeque<St>()
        wl.add(St(0, entryFrameRegs(method), null, null, entryOffset, -1))
        for (hOff in handlerOffsets) {
            val hi = method.offsetToIndex[hOff] ?: continue
            wl.add(St(hi, entryFrameRegs(method), null, null, hOff, -1))
        }
        var complete = true
        var steps = 0
        val cap = (insns.size + 4) * 256 + 4096

        while (wl.isNotEmpty()) {
            if (steps++ > cap || vm.deadlineExceeded()) { complete = false; break }
            val st = wl.removeFirst()
            val off = insns[st.pc].offset
            var cur = st.lastNode
            var regs = st.regs
            if (off in nodeSet && off != st.lastNode) {
                edges.getOrPut(st.lastNode) { HashSet() }.add(off)
                if (st.lastStr >= 0) strTarget[st.lastStr] = off
                cur = off
                val rep = nodeRegs[off]
                if (rep != null) {
                    regs = Array(regs.size) { i -> if (slotEq(regs[i], rep.getOrNull(i))) regs[i] else widen(regs[i], rep.getOrNull(i)) }
                    nodeRegs[off] = regs
                } else nodeRegs[off] = regs.copyOf()
            }
            if (!visited.add(keyOf(st.pc, regs, st.result, st.lastStr))) continue

            val frame = Frame(regs.size)
            System.arraycopy(regs, 0, frame.regs, 0, regs.size)
            frame.result = st.result
            frame.resultType = st.resultType
            val res = try { interp.absStep(method, insns[st.pc], frame, st.pc, resolver, heap) }
                catch (e: Throwable) { complete = false; continue }
            val ci = insns[st.pc]
            val stateAssign = ci.regs.getOrNull(0) == dispatchReg &&
                (if (state.isString) ci.opcode == Opcode.CONST_STRING else ci.opcode == Opcode.CONST)
            val newStr = if (stateAssign) off else st.lastStr
            if (res.returns) { terminals.add(cur); continue }
            val forked = (ci.opcode == Opcode.PACKED_SWITCH || ci.opcode == Opcode.SPARSE_SWITCH) && res.successors.size > 1
            val succStr = if (forked) -1 else newStr
            for (succOff in res.successors) {
                val si = method.offsetToIndex[succOff] ?: continue
                wl.add(St(si, frame.regs.copyOf(), frame.result, frame.resultType, cur, succStr))
            }
        }
        return DispatchGraph(entryOffset, nodeSet, edges, terminals, complete, strTarget)
    }

    private fun keyOf(pc: Int, regs: Array<Any?>, result: Any?, lastStr: Int): Long =
        (pc.toLong() shl 40) xor regKey(regs, result) xor (lastStr.toLong() * 1000000007L)

    private fun slotEq(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is UnknownVal && b is UnknownVal -> true
        a is UnknownVal || b is UnknownVal -> false
        else -> a == b
    }

    private fun widen(a: Any?, b: Any?): Any? {
        if (a is UnknownVal) return a
        val t = when (val v = a ?: b) {
            is UnknownVal -> v.type
            is Int -> "I"; is Long -> "J"; is Boolean -> "Z"; is Char -> "C"
            is String -> "Ljava/lang/String;"
            else -> "Ljava/lang/Object;"
        }
        return UnknownVal(t)
    }

    private fun regKey(regs: Array<Any?>, result: Any?): Long {
        var h = 1125899906842597L
        for (v in regs) h = 31 * h + slotKey(v)
        return 31 * h + slotKey(result)
    }

    private fun slotKey(v: Any?): Long = when (v) {
        null -> 0
        is UnknownVal -> 1
        is String -> v.hashCode().toLong()
        is Int -> v.toLong() * 7 + 3
        is Long -> v * 11 + 5
        is Boolean -> if (v) 13 else 17
        is Char -> v.code.toLong() * 19
        else -> 2
    }

    private class DispatchState(val reg: Int, val isString: Boolean)

    private fun dispatchStateOf(method: DexMethod): DispatchState? {
        if (method.insns.none { it.payload is SwitchPayload }) return null
        stringDispatchReg(method)?.let { return DispatchState(it, true) }
        intDispatchReg(method)?.let { return DispatchState(it, false) }
        return null
    }

    private fun stringDispatchReg(method: DexMethod): Int? {
        val insns = method.insns
        val hc = insns.firstOrNull {
            it.opcode == Opcode.INVOKE_VIRTUAL && (it.ref as? MethodRef).let { r -> r?.name == "hashCode" && r.declClass == "Ljava/lang/String;" }
        } ?: return null
        val reg = hc.regs.getOrNull(0) ?: return null
        return if (insns.count { it.opcode == Opcode.CONST_STRING && it.regs.getOrNull(0) == reg } >= 3) reg else null
    }

    private fun intDispatchReg(method: DexMethod): Int? {
        val insns = method.insns
        for (sw in insns) {
            if (sw.payload !is SwitchPayload) continue
            val reg = sw.regs.getOrNull(0) ?: continue
            if (insns.none { it.opcode in DECODE_OPS && it.regs.getOrNull(0) == reg }) continue
            val backEdge = insns.any { it.opcode == Opcode.GOTO && it.offset > sw.offset && it.target <= sw.offset }
            if (!backEdge) continue
            if (insns.count { it.opcode == Opcode.CONST && it.regs.getOrNull(0) == reg } < 2) continue
            if (constClosed(method, reg)) return reg
        }
        return null
    }

    private fun constClosed(method: DexMethod, reg: Int): Boolean {
        val known = HashMap<Int, Boolean>()
        fun rec(r: Int, stack: Set<Int>): Boolean {
            known[r]?.let { return it }
            if (r in stack) return true
            if (r >= method.registersCount - method.paramWords) { known[r] = false; return false }
            if (method.insns.any { it.regs.getOrNull(0) == r && it.opcode in DATA_DEST }) { known[r] = false; return false }
            val defs = method.insns.filter { it.regs.getOrNull(0) == r && (it.opcode == Opcode.CONST || it.opcode in DECODE_OPS) }
            if (defs.isEmpty()) { known[r] = false; return false }
            val ok = defs.all { d -> d.opcode == Opcode.CONST || d.regs.drop(1).all { s -> s == r || rec(s, stack + r) } }
            known[r] = ok
            return ok
        }
        return rec(reg, emptySet())
    }

    private companion object {
        val DECODE_OPS = setOf(
            Opcode.XOR_INT, Opcode.XOR_INT_LIT, Opcode.ADD_INT, Opcode.ADD_INT_LIT,
            Opcode.SUB_INT, Opcode.RSUB_INT, Opcode.MUL_INT, Opcode.MUL_INT_LIT,
            Opcode.AND_INT, Opcode.AND_INT_LIT, Opcode.OR_INT, Opcode.OR_INT_LIT,
            Opcode.SHL_INT, Opcode.SHL_INT_LIT, Opcode.SHR_INT, Opcode.SHR_INT_LIT,
            Opcode.USHR_INT, Opcode.USHR_INT_LIT, Opcode.NEG_INT, Opcode.NOT_INT,
        )
        val DATA_DEST = setOf(
            Opcode.IGET, Opcode.SGET, Opcode.AGET, Opcode.AGET_BOOLEAN, Opcode.AGET_BYTE,
            Opcode.AGET_BYTE_BOOLEAN, Opcode.AGET_CHAR, Opcode.AGET_SHORT, Opcode.AGET_WIDE,
            Opcode.AGET_OBJECT, Opcode.ARRAY_LENGTH, Opcode.INSTANCE_OF, Opcode.MOVE,
            Opcode.MOVE_OBJECT, Opcode.MOVE_WIDE, Opcode.MOVE_RESULT, Opcode.MOVE_EXCEPTION,
            Opcode.MOVE_MULTI,
        )
    }
}
