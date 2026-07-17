package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.DvmClass
import io.github.nitanmarcel.jdex.exec.DvmMethodHandle
import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import jadx.api.plugins.input.insns.Opcode

data class ReflectInvokeTarget(val ref: MethodRef, val isStatic: Boolean)

class ReflectionPass : AnalysisPass {

    fun invokeTargets(method: DexMethod, result: DataflowResult): Map<Int, ReflectInvokeTarget> {
        val out = LinkedHashMap<Int, ReflectInvokeTarget>()
        for (insn in method.insns) {
            if (insn.opcode !in INVOKE_OPCODES || insn.regs.isEmpty()) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (ref.declClass != "Ljava/lang/reflect/Method;" || ref.name != "invoke") continue
            val h = result.regInAtOffset(insn.offset, insn.regs[0]) as? DvmMethodHandle ?: continue
            h.dexMethod?.let { out[insn.offset] = ReflectInvokeTarget(it.ref, it.isStatic); continue }
            h.hostMethod?.let { out[insn.offset] = ReflectInvokeTarget(hostMethodRef(it), java.lang.reflect.Modifier.isStatic(it.modifiers)) }
        }
        return out
    }

    override fun run(method: DexMethod, result: DataflowResult): List<InsnAnnotation> {
        val descriptor = "${method.declClass}->${method.ref.shortId}"
        val out = ArrayList<InsnAnnotation>()
        val insns = method.insns
        for (i in insns.indices) {
            val insn = insns[i]
            if (insn.opcode !in INVOKE_OPCODES) continue
            val ref = insn.ref as? MethodRef ?: continue

            if (ref.declClass == "Ljava/lang/reflect/Method;" && ref.name == "invoke" && insn.regs.isNotEmpty()) {
                renderHandle(result.regInAtOffset(insn.offset, insn.regs[0]))
                    ?.let { out += InsnAnnotation(descriptor, insn.offset, "invokes $it") }
                continue
            }

            val next = insns.getOrNull(i + 1) ?: continue
            if (next.opcode != Opcode.MOVE_RESULT || next.regs.isEmpty()) continue
            when (val v = result.regOut(i + 1, next.regs[0])) {
                is DvmClass -> out += InsnAnnotation(descriptor, insn.offset, "→ ${dotted(v.desc)}")
                is DvmMethodHandle -> renderHandle(v)?.let { out += InsnAnnotation(descriptor, insn.offset, "→ $it") }
            }
        }
        return out
    }

    private fun renderHandle(v: Any?): String? {
        val h = v as? DvmMethodHandle ?: return null
        h.dexMethod?.let { m -> return "${dotted(m.declClass)}.${m.ref.name}(${m.ref.argTypes.joinToString(", ") { simple(it) }})" }
        h.hostMethod?.let { m -> return "${m.declaringClass.simpleName}.${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})" }
        h.symbol?.let { r -> return "${dotted(r.declClass)}.${r.name}(${r.argTypes.joinToString(", ") { simple(it) }})" }
        return null
    }

    private fun dotted(desc: String): String = desc.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun simple(desc: String): String = when {
        desc.startsWith("[") -> simple(desc.substring(1)) + "[]"
        desc.startsWith("L") -> desc.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        else -> PRIMS[desc] ?: desc
    }

    companion object {
        private val PRIMS = mapOf("I" to "int", "J" to "long", "Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short", "F" to "float", "D" to "double", "V" to "void")
    }
}
