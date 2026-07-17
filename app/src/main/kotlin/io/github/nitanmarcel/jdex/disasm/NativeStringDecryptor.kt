package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation
import io.github.nitanmarcel.jdex.exec.model.ArrayPayload
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.model.StringRef
import jadx.api.plugins.input.insns.Opcode

class NativeStringDecryptor(private val soBytes: ByteArray, private val bridge: JavaBridge? = null) : AutoCloseable {

    enum class ArgKind { STRING, BYTES }

    data class Candidate(val name: String, val signature: String, val argKind: ArgKind)

    private val elf = ElfFile.parse(soBytes)

    private val emulatorLazy = lazy { elf?.let { runCatching { NativeEmulator(it, soBytes, bridge = bridge) }.getOrNull() } }
    private val emulator: NativeEmulator? get() = emulatorLazy.value

    private val candidatesCache: List<Candidate> by lazy {
        val e = elf ?: return@lazy emptyList()
        if (e.functions.none { it.name == "JNI_OnLoad" || it.name == "JNI_OnUnload" || it.name.startsWith("Java_") }) return@lazy emptyList()
        NativeFunctions.detectArmMode(e, CapstoneDisassembler, e.littleEndian)
        val starts = NativeFunctions.discover(e, CapstoneDisassembler, e.arch, e.littleEndian)
        val jni = NativeJni.analyze(e, CapstoneDisassembler, e.arch, e.littleEndian, starts)
        jni.registered.values.mapNotNull(::classify).distinctBy { it.name + it.signature }
    }

    fun candidates(): List<Candidate> = candidatesCache

    private fun classify(n: NativeJni.JniNative): Candidate? {
        if (!n.signature.endsWith(")Ljava/lang/String;")) return null
        return when (n.signature.substringAfter('(').substringBefore(')')) {
            "Ljava/lang/String;" -> Candidate(n.name, n.signature, ArgKind.STRING)
            "[B" -> Candidate(n.name, n.signature, ArgKind.BYTES)
            else -> null
        }
    }

    fun recover(className: String, candidate: Candidate, cipher: Any): String? =
        emulator?.let { runCatching { it.callStaticString(className, candidate.name + candidate.signature, cipher) }.getOrNull() }

    fun recoverCallSites(callers: List<DexMethod>): List<InsnAnnotation> {
        val byDescriptor = candidates().associateBy { it.name + it.signature }
        if (byDescriptor.isEmpty()) return emptyList()
        val out = ArrayList<InsnAnnotation>()
        for (m in callers) {
            for (i in m.insns.indices) {
                val insn = m.insns[i]
                if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) continue
                val ref = insn.ref as? MethodRef ?: continue
                val cand = byDescriptor["${ref.name}(${ref.argTypes.joinToString("")})${ref.returnType}"] ?: continue
                val argReg = insn.regs.firstOrNull() ?: continue
                val cipher = when (cand.argKind) {
                    ArgKind.BYTES -> backsliceBytes(m, i, argReg)
                    ArgKind.STRING -> backsliceString(m, i, argReg)
                } ?: continue
                val plain = recover(ref.declClass.removePrefix("L").removeSuffix(";"), cand, cipher) ?: continue
                out.add(InsnAnnotation("${m.declClass}->${m.ref.shortId}", insn.offset, render(plain)))
            }
        }
        return out
    }

    private fun render(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun backsliceBytes(m: DexMethod, callIndex: Int, reg: Int): ByteArray? {
        for (j in callIndex - 1 downTo 0) {
            val insn = m.insns[j]
            if (insn.opcode == Opcode.FILL_ARRAY_DATA && insn.regs.firstOrNull() == reg) {
                val data = (insn.payload as? ArrayPayload)?.data ?: return null
                return ByteArray(java.lang.reflect.Array.getLength(data)) { (java.lang.reflect.Array.get(data, it) as Number).toByte() }
            }
            if (insn.opcode == Opcode.NEW_ARRAY && insn.regs.firstOrNull() == reg) return null
        }
        return null
    }

    private fun backsliceString(m: DexMethod, callIndex: Int, reg: Int): String? {
        for (j in callIndex - 1 downTo 0) {
            val insn = m.insns[j]
            if (insn.opcode == Opcode.CONST_STRING && insn.regs.firstOrNull() == reg) return (insn.ref as? StringRef)?.value
        }
        return null
    }

    override fun close() {
        if (emulatorLazy.isInitialized()) emulatorLazy.value?.close()
    }
}
