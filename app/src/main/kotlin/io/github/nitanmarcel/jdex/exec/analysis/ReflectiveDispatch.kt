package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.DvmField
import io.github.nitanmarcel.jdex.exec.DvmMethodHandle
import io.github.nitanmarcel.jdex.exec.hostClass
import io.github.nitanmarcel.jdex.exec.ExecHook
import io.github.nitanmarcel.jdex.exec.Frame
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.model.SwitchPayload
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode
import java.util.Optional

class DispatchTarget(
    val owner: String,
    val member: String,
    val memberType: String,
    val isField: Boolean,
    val isStatic: Boolean,
    val methodRef: MethodRef? = null,
)

data class ReflectFieldSite(
    val offset: Int,
    val owner: String,
    val member: String,
    val memberType: String,
    val isStatic: Boolean,
    val deadOffsets: Set<Int> = emptySet(),
)

data class ReflectInvokeSite(
    val offset: Int,
    val ref: MethodRef,
    val isStatic: Boolean,
    val operandRegs: List<Int>,
    val deadOffsets: Set<Int>,
)

class ReflectiveDispatch(private val source: MethodSource) {
    private val cache = HashMap<String, Optional<DispatchTarget>>()

    private val reflectiveCache = HashMap<String, Boolean>()

    fun isReflectiveDispatcher(dispatcherClass: String, methodShortId: String): Boolean =
        reflectiveCache.getOrPut("$dispatcherClass#$methodShortId") {
            val m = source.method(dispatcherClass, methodShortId) ?: return@getOrPut false
            if (m.ref.argTypes.firstOrNull() != "I") return@getOrPut false
            reachesReflection(m, 0, HashSet())
        }

    private fun reachesReflection(m: DexMethod, depth: Int, seen: MutableSet<String>): Boolean {
        if (depth > 8 || !seen.add("${m.declClass}#${m.ref.shortId}")) return false
        for (insn in m.insns) {
            val ref = insn.ref as? MethodRef ?: continue
            if (ref.declClass in REFLECT_SINKS) return true
            if (ref.declClass == "Ljava/lang/Class;" && ref.name in REFLECT_LOOKUPS) return true
            source.method(ref.declClass, ref.shortId)?.let { if (reachesReflection(it, depth + 1, seen)) return true }
        }
        return false
    }

    fun resolve(dispatcherClass: String, methodShortId: String, index: Int): DispatchTarget? =
        cache.getOrPut("$dispatcherClass#$methodShortId#$index") {
            Optional.ofNullable(runCatching { doResolve(dispatcherClass, methodShortId, index) }.getOrNull())
        }.orElse(null)

    fun fieldSites(method: DexMethod): List<ReflectFieldSite> {
        val df = runCatching { Dataflow(Vm(source)).analyze(method) }.getOrNull() ?: return emptyList()
        val insns = method.insns
        val reach = InsnReach(method)
        val branchTargets = branchTargetsOf(insns)
        val out = ArrayList<ReflectFieldSite>()
        for (ci in insns.indices) {
            val insn = insns[ci]
            if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (!isReflectiveDispatcher(ref.declClass, ref.shortId)) continue
            val idxReg = insn.regs.getOrNull(0) ?: continue
            val index = df.regInAtOffset(insn.offset, idxReg) as? Int ?: continue
            val target = resolve(ref.declClass, ref.shortId, index) ?: continue
            if (!target.isField) continue
            val dead = emptyArrayScaffold(insns, ci, ref, reach, branchTargets)
            out.add(ReflectFieldSite(insn.offset, target.owner, target.member, target.memberType, target.isStatic, dead))
        }
        return out
    }

    private fun emptyArrayScaffold(insns: List<DalvikInsn>, ci: Int, ref: MethodRef, reach: InsnReach, branchTargets: Set<Int>): Set<Int> {
        if (ref.argTypes.size != 3) return emptySet()
        val arrReg = insns[ci].regs.getOrNull(2) ?: return emptySet()
        val newIdx = lastDef(insns, ci, arrReg) ?: return emptySet()
        if (insns[newIdx].opcode != Opcode.NEW_ARRAY) return emptySet()
        for (j in newIdx + 1 until ci) if (insns[j].opcode == Opcode.APUT_OBJECT && insns[j].regs.getOrNull(1) == arrReg) return emptySet()
        if (reach.hasExternalReader(newIdx, arrReg, setOf(newIdx, ci))) return emptySet()
        if (insns[newIdx].offset in branchTargets) return emptySet()
        return setOf(insns[newIdx].offset)
    }

    private fun branchTargetsOf(insns: List<DalvikInsn>): Set<Int> {
        val t = HashSet<Int>()
        for (ins in insns) when (ins.opcode) {
            Opcode.GOTO -> t.add(ins.target)
            in InsnReach.IF_OPS -> t.add(ins.target)
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH ->
                (ins.payload as? SwitchPayload)?.targets?.forEach { t.add(ins.offset + it) }
            else -> {}
        }
        return t
    }

    fun invokeSites(method: DexMethod): List<ReflectInvokeSite> {
        val df = runCatching { Dataflow(Vm(source)).analyze(method) }.getOrNull() ?: return emptyList()
        val insns = method.insns
        val reach = InsnReach(method)
        val branchTargets = branchTargetsOf(insns)
        val out = ArrayList<ReflectInvokeSite>()
        for (ci in insns.indices) {
            val insn = insns[ci]
            if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (ref.argTypes.size != 3 || !isReflectiveDispatcher(ref.declClass, ref.shortId)) continue
            val idxReg = insn.regs.getOrNull(0) ?: continue
            val index = df.regInAtOffset(insn.offset, idxReg) as? Int ?: continue
            val arrReg = insn.regs.getOrNull(2) ?: continue
            val defIdx = lastDef(insns, ci, arrReg) ?: continue
            val defInsn = insns[defIdx]

            val deadIdx = LinkedHashSet<Int>()
            val checks = ArrayList<Pair<Int, Int>>()

            val byIndex = HashMap<Int, Pair<Int, Int>>()
            when {
                defInsn.opcode == Opcode.NEW_ARRAY -> {
                    deadIdx.add(defIdx); checks.add(defIdx to arrReg)
                    for (j in defIdx + 1 until ci) {
                        val a = insns[j]
                        if (a.opcode == Opcode.APUT_OBJECT && a.regs.getOrNull(1) == arrReg) {
                            val ai = df.regInAtOffset(a.offset, a.regs[2]) as? Int ?: continue
                            byIndex[ai] = a.regs[0] to j
                            deadIdx.add(j)
                        }
                    }
                }
                defInsn.opcode == Opcode.MOVE_RESULT && defIdx > 0 &&
                    insns[defIdx - 1].opcode == Opcode.FILLED_NEW_ARRAY -> {
                    deadIdx.add(defIdx); deadIdx.add(defIdx - 1); checks.add(defIdx to arrReg)
                    insns[defIdx - 1].regs.forEachIndexed { k, r -> byIndex[k] = r to (defIdx - 1) }
                }
                else -> continue
            }
            val target = resolveInvoke(ref.declClass, ref.shortId, index, byIndex.size) ?: continue
            if (byIndex.size != target.ref.argTypes.size) continue

            val operandRegs = ArrayList<Int>()
            var ok = true
            for (k in target.ref.argTypes.indices) {
                val cap = byIndex[k]
                if (cap == null) { ok = false; break }
                val (finalReg, capIdx) = unboxOperand(insns, cap.first, cap.second, target.ref.argTypes[k], deadIdx, checks)
                if (reach.reachingDefs(ci, finalReg) != reach.reachingDefs(capIdx, finalReg)) { ok = false; break }
                operandRegs.add(finalReg)
            }
            if (!ok) continue

            val allowed = HashSet(deadIdx).apply { add(ci) }
            if (checks.any { (d, r) -> reach.hasExternalReader(d, r, allowed) }) continue

            val deadOffsets = deadIdx.mapTo(HashSet()) { insns[it].offset }
            val safe = if (deadOffsets.any { it in branchTargets }) emptySet() else deadOffsets
            out.add(ReflectInvokeSite(insn.offset, target.ref, target.isStatic, operandRegs, safe))
        }
        return out
    }

    private fun lastDef(insns: List<DalvikInsn>, before: Int, reg: Int): Int? {
        for (i in before - 1 downTo 0) {
            if (insns[i].opcode in DEFS_FIRST_REG && insns[i].regs.getOrNull(0) == reg) return i
        }
        return null
    }

    private fun unboxOperand(insns: List<DalvikInsn>, vreg: Int, aputIdx: Int, paramType: String, deadIdx: MutableSet<Int>, checks: MutableList<Pair<Int, Int>>): Pair<Int, Int> {
        val box = BOX_CLASSES[paramType] ?: return vreg to aputIdx
        val di = lastDef(insns, aputIdx, vreg) ?: return vreg to aputIdx
        if (insns[di].opcode != Opcode.MOVE_RESULT || di == 0) return vreg to aputIdx
        val inv = insns[di - 1]
        val r = inv.ref as? MethodRef ?: return vreg to aputIdx
        if (r.name != "valueOf" || r.declClass != box || inv.regs.isEmpty()) return vreg to aputIdx
        deadIdx.add(di); deadIdx.add(di - 1); checks.add(di to vreg)
        return inv.regs[0] to (di - 1)
    }

    fun annotations(method: DexMethod, result: DataflowResult): List<InsnAnnotation> {
        val desc = "${method.declClass}->${method.ref.shortId}"
        val out = ArrayList<InsnAnnotation>()
        for (insn in method.insns) {
            if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (ref.returnType != "Ljava/lang/Object;" || ref.argTypes.firstOrNull() != "I" || insn.regs.isEmpty()) continue
            val index = result.regInAtOffset(insn.offset, insn.regs[0]) as? Int ?: continue
            val t = resolve(ref.declClass, ref.shortId, index) ?: continue
            out += InsnAnnotation(desc, insn.offset, render(t))
        }
        return out
    }

    private fun render(t: DispatchTarget): String {
        val owner = t.owner.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        return if (t.isField) "= $owner.${t.member}" else "invokes $owner.${t.member}()"
    }

    private fun doResolve(dispatcherClass: String, methodShortId: String, index: Int): DispatchTarget? {
        val m = source.method(dispatcherClass, methodShortId) ?: return null
        if (m.ref.argTypes.firstOrNull() != "I") return null
        var hit: DispatchTarget? = null
        val hook = object : ExecHook {
            override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
                if (hit != null || insn.opcode !in INVOKE_OPCODES) return
                val vals = insn.regs.map { frame.get(it) }
                vals.firstNotNullOfOrNull { it as? DvmField }?.let { hit = fieldTarget(it); return }
                vals.firstNotNullOfOrNull { it as? DvmMethodHandle }?.let { h -> methodTarget(h)?.let { hit = it; return } }
                val strs = vals.filterIsInstance<String>()
                for (cs in strs) {
                    val owner = descOf(cs)
                    val ci = source.classInfo(owner)
                    val host = if (ci == null) runCatching { hostClass(owner) }.getOrNull() else null
                    if (ci == null && host == null) continue
                    for (ns in strs) {
                        if (ns === cs) continue
                        if (ci != null) {
                            ci.fields.firstOrNull { it.ref.name == ns }?.let { hit = DispatchTarget(owner, ns, it.ref.type, true, it.isStatic); return }
                            source.methodsByName(owner, ns).singleOrNull()?.let { hit = DispatchTarget(owner, ns, it.ref.returnType, false, it.isStatic, it.ref); return }
                        } else {
                            host!!.fields.firstOrNull { it.name == ns }?.let { hit = DispatchTarget(owner, ns, td(it.type), true, java.lang.reflect.Modifier.isStatic(it.modifiers)); return }
                            host.methods.filter { it.name == ns }.singleOrNull()?.let {
                                val ref = MethodRef(owner, ns, it.parameterTypes.map { p -> td(p) }, td(it.returnType))
                                hit = DispatchTarget(owner, ns, td(it.returnType), false, java.lang.reflect.Modifier.isStatic(it.modifiers), ref); return
                            }
                        }
                    }
                }
            }
        }
        val args = ArrayList<Any?>()
        args.add(index)
        for (t in m.ref.argTypes.drop(1)) args.add(UnknownVal(t))
        runCatching { Vm(source, hook = hook).invoke(m, args) }
        return hit
    }

    private fun fieldTarget(f: DvmField) = DispatchTarget(f.ref.declClass, f.ref.name, f.ref.type, true, f.isStatic)

    private fun methodTarget(h: DvmMethodHandle): DispatchTarget? {
        h.dexMethod?.let { dm ->
            return DispatchTarget(dm.declClass, dm.ref.name, dm.ref.returnType, false, dm.isStatic, dm.ref)
        }
        val hm = h.hostMethod ?: return null
        val ref = hostMethodRef(hm)
        return DispatchTarget(ref.declClass, ref.name, ref.returnType, false, java.lang.reflect.Modifier.isStatic(hm.modifiers), ref)
    }

    fun resolveInvoke(dispatcherClass: String, methodShortId: String, index: Int, argCount: Int): ReflectInvokeTarget? {
        val t = resolve(dispatcherClass, methodShortId, index) ?: return null
        if (t.isField) return null
        t.methodRef?.let { return ReflectInvokeTarget(it, t.isStatic) }
        source.classInfo(t.owner)?.let {
            val m = source.methodsByName(t.owner, t.member).firstOrNull { it.ref.argTypes.size == argCount } ?: return null
            return ReflectInvokeTarget(m.ref, t.isStatic)
        }
        val host = runCatching { hostClass(t.owner) }.getOrNull() ?: return null
        val hm = host.methods.firstOrNull { it.name == t.member && it.parameterCount == argCount } ?: return null
        return ReflectInvokeTarget(MethodRef(t.owner, t.member, hm.parameterTypes.map { td(it) }, td(hm.returnType)), java.lang.reflect.Modifier.isStatic(hm.modifiers))
    }


    private fun td(c: Class<*>): String = classDescriptor(c)

    private fun descOf(dotted: String): String =
        if (dotted.startsWith("[") || dotted.length == 1) dotted else "L" + dotted.replace('.', '/') + ";"

    private companion object {
        val REFLECT_SINKS = setOf("Ljava/lang/reflect/Field;", "Ljava/lang/reflect/Method;", "Ljava/lang/reflect/Constructor;", "Ljava/lang/reflect/Array;")
        val REFLECT_LOOKUPS = setOf(
            "forName", "getDeclaredField", "getDeclaredMethod", "getField", "getMethod",
            "getFields", "getMethods", "getDeclaredFields", "getDeclaredMethods", "getDeclaredConstructor", "getConstructor", "newInstance",
        )
        val DEFS_FIRST_REG = setOf(
            Opcode.CONST, Opcode.CONST_WIDE, Opcode.CONST_STRING, Opcode.NEW_ARRAY, Opcode.MOVE_RESULT,
            Opcode.MOVE, Opcode.MOVE_OBJECT, Opcode.MOVE_WIDE,
        )
        val BOX_CLASSES = mapOf(
            "I" to "Ljava/lang/Integer;", "J" to "Ljava/lang/Long;", "Z" to "Ljava/lang/Boolean;",
            "B" to "Ljava/lang/Byte;", "C" to "Ljava/lang/Character;", "S" to "Ljava/lang/Short;",
            "F" to "Ljava/lang/Float;", "D" to "Ljava/lang/Double;",
        )
    }
}
