package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.JniName
import io.github.nitanmarcel.jdex.disasm.NativeJni

data class DexNativeMethod(
    val descriptor: String, val className: String, val name: String, val signature: String,
    val isStatic: Boolean = false,
)

class BindingMap {
    private val entryByDescriptor = HashMap<String, Pair<String, Long>>()
    private val callSitesById = HashMap<String, Set<Long>>()
    private val entryMeta = HashMap<Pair<String, Long>, Pair<String, Boolean>>()

    fun add(nativeId: String, analysis: NativeJni.Analysis, exports: Map<String, Long>, dexNatives: List<DexNativeMethod>) {
        callSitesById[nativeId] = analysis.envFns.keys.toSet()
        val byNameSig = dexNatives.associateBy { it.name to it.signature }
        for ((vaddr, jni) in analysis.registered) {
            val m = byNameSig[jni.name to jni.signature] ?: continue
            entryByDescriptor[m.descriptor] = nativeId to vaddr
            entryMeta[nativeId to vaddr] = m.signature to m.isStatic
        }
        for ((symbol, vaddr) in exports) {
            val m = matchExport(symbol, dexNatives) ?: continue
            entryByDescriptor[m.descriptor] = nativeId to vaddr
            entryMeta[nativeId to vaddr] = m.signature to m.isStatic
        }
    }

    fun entryAt(nativeId: String, vaddr: Long): Pair<String, Boolean>? = entryMeta[nativeId to vaddr]

    fun nativeEntry(descriptor: String): Pair<String, Long>? = entryByDescriptor[descriptor]

    fun allEntries(): List<Pair<String, Long>> = entryByDescriptor.values.distinct()

    fun callSites(nativeId: String): Set<Long> = callSitesById[nativeId] ?: emptySet()

    fun isNativeMethod(descriptor: String): Boolean = descriptor in entryByDescriptor

    private fun matchExport(symbol: String, dexNatives: List<DexNativeMethod>): DexNativeMethod? {
        val (cls, method) = JniName.demangle(symbol) ?: return null
        val raw = cls.replace('.', '/')
        val candidates = dexNatives.filter { it.className.replace('.', '/') == raw && it.name == method }
        if (candidates.size == 1) return candidates[0]
        val args = JniName.demangleArgs(symbol) ?: return null
        return candidates.firstOrNull { it.signature.removePrefix("(").substringBefore(")") == args }
    }
}
