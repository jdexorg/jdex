package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import jadx.api.plugins.input.insns.Opcode

internal const val NO_DEF = -1

internal fun classDescriptor(c: Class<*>): String = when {
    c == java.lang.Void.TYPE -> "V"; c == Integer.TYPE -> "I"; c == java.lang.Long.TYPE -> "J"
    c == java.lang.Boolean.TYPE -> "Z"; c == java.lang.Byte.TYPE -> "B"; c == Character.TYPE -> "C"
    c == java.lang.Short.TYPE -> "S"; c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"
    c.isArray -> c.name.replace('.', '/')
    else -> "L" + c.name.replace('.', '/') + ";"
}

internal fun hostMethodRef(m: java.lang.reflect.Method): MethodRef =
    MethodRef(classDescriptor(m.declaringClass), m.name, m.parameterTypes.map { classDescriptor(it) }, classDescriptor(m.returnType))

internal class InsnReach(private val method: DexMethod) {
    private val insns = method.insns
    private val preds: Map<Int, List<Int>> by lazy {
        val m = HashMap<Int, MutableList<Int>>()
        for (i in insns.indices) for (s in successorIndices(i)) m.getOrPut(s) { ArrayList() }.add(i)
        m
    }

    fun definesReg(insn: DalvikInsn): Int? = if (insn.opcode in NON_DEFINING) null else insn.regs.getOrNull(0)

    fun reachingDefs(before: Int, reg: Int): Set<Int> {
        val starts = preds[before]
        if (starts.isNullOrEmpty()) return setOf(NO_DEF)
        val result = HashSet<Int>()
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Int>().apply { starts.forEach { addLast(it) } }
        while (stack.isNotEmpty()) {
            val j = stack.removeLast()
            if (!visited.add(j)) continue
            if (definesReg(insns[j]) == reg) { result.add(j); continue }
            val ps = preds[j]
            if (ps.isNullOrEmpty()) result.add(NO_DEF) else ps.forEach { stack.addLast(it) }
        }
        return result
    }

    fun hasExternalReader(defIdx: Int, reg: Int, allowed: Set<Int>): Boolean {
        for (j in insns.indices) {
            if (j in allowed) continue
            val ins = insns[j]
            val srcStart = if (definesReg(ins) != null) 1 else 0
            var reads = false
            for (p in srcStart until ins.regs.size) if (ins.regs[p] == reg) { reads = true; break }
            if (reads && defIdx in reachingDefs(j, reg)) return true
        }
        return false
    }

    private fun successorIndices(i: Int): List<Int> {
        val insn = insns[i]
        val o2i = method.offsetToIndex
        val out = ArrayList<Int>(2)
        fun add(off: Int) { o2i[off]?.let { out.add(it) } }
        fun fall() { if (i + 1 in insns.indices) out.add(i + 1) }
        when (insn.opcode) {
            Opcode.RETURN, Opcode.RETURN_VOID, Opcode.THROW -> {}
            Opcode.GOTO -> add(insn.target)
            in IF_OPS -> { add(insn.target); fall() }
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                (insn.payload as? SwitchPayload)?.let { sw -> for (t in sw.targets) add(insn.offset + t) }
                fall()
            }
            else -> fall()
        }
        return out
    }

    companion object {
        val IF_OPS = setOf(
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        )
        val NON_DEFINING = setOf(
            Opcode.NOP, Opcode.GOTO, Opcode.RETURN, Opcode.RETURN_VOID, Opcode.THROW,
            Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT, Opcode.CHECK_CAST, Opcode.FILL_ARRAY_DATA,
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH,
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
            Opcode.APUT, Opcode.APUT_BOOLEAN, Opcode.APUT_BYTE, Opcode.APUT_BYTE_BOOLEAN, Opcode.APUT_CHAR,
            Opcode.APUT_SHORT, Opcode.APUT_OBJECT, Opcode.APUT_WIDE,
            Opcode.IPUT, Opcode.SPUT,
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
        )
    }
}
