package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import io.github.nitanmarcel.jdex.exec.runtime.UninitHost
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import io.github.nitanmarcel.jdex.exec.runtime.WideHigh
import java.util.IdentityHashMap

class EngineContext(private val source: MethodSource, private val limits: ExecLimits = ExecLimits()) {

    companion object {
        val MUTABLE_CONTAINER_TYPES: Set<String> = setOf(
            "Ljava/util/Collection;", "Ljava/util/List;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;",
            "Ljava/util/Vector;", "Ljava/util/Stack;", "Ljava/util/AbstractList;", "Ljava/util/AbstractCollection;",
            "Ljava/util/Set;", "Ljava/util/HashSet;", "Ljava/util/LinkedHashSet;", "Ljava/util/TreeSet;", "Ljava/util/AbstractSet;",
            "Ljava/util/Map;", "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;",
            "Ljava/util/Hashtable;", "Ljava/util/AbstractMap;", "Ljava/util/concurrent/ConcurrentHashMap;",
            "Ljava/util/Queue;", "Ljava/util/Deque;", "Ljava/util/ArrayDeque;", "Ljava/util/PriorityQueue;",
            "Ljava/lang/StringBuilder;", "Ljava/lang/StringBuffer;",
        )
    }

    private class Uncopyable(val value: Any) : RuntimeException()

    private val snapshots = HashMap<String, HashMap<String, Any?>?>()

    @Synchronized
    fun clinitStaticsFor(desc: String): HashMap<String, Any?>? {
        if (source.classInfo(desc) == null) return null
        if (!snapshots.containsKey(desc)) snapshots[desc] = computeSnapshot(desc)
        val base = snapshots[desc] ?: return null
        val seen = IdentityHashMap<Any, Any>()
        return HashMap<String, Any?>(base.size).apply { for ((k, v) in base) put(k, deepCopy(v, seen)) }
    }

    private fun computeSnapshot(desc: String): HashMap<String, Any?>? {
        val vm = Vm(source, limits = limits)
        runCatching { vm.ensureClinit(desc) }
        val allowed = superChain(desc)
        if (vm.initialized().any { it !in allowed }) return null
        val base = vm.statics[desc] ?: return HashMap()
        return try {
            val seen = IdentityHashMap<Any, Any>()
            HashMap<String, Any?>(base.size).apply { for ((k, v) in base) put(k, deepCopy(v, seen)) }
        } catch (e: Uncopyable) {
            null
        }
    }

    val mutableFields: Set<String> by lazy {
        val out = HashSet<String>()
        for (m in source.allMethods()) {
            val n = m.ref.name
            for (insn in m.insns) {
                val op = insn.opcode.name
                val isSput = op.startsWith("SPUT") && n != "<clinit>"
                val isIput = op.startsWith("IPUT") && n != "<init>" && n != "<clinit>"
                if (isSput || isIput) {
                    (insn.ref as? io.github.nitanmarcel.jdex.exec.model.FieldRef)?.let { out += it.key }
                }
            }
        }
        out
    }

    private fun superChain(desc: String): Set<String> {
        val out = HashSet<String>()
        var c: String? = desc
        while (c != null && out.add(c)) c = source.classInfo(c)?.superType
        return out
    }

    private fun deepCopy(v: Any?, seen: IdentityHashMap<Any, Any>): Any? = when (v) {
        null, is Int, is Long, is Float, is Double, is Boolean, is Char, is Byte, is Short, is String,
        is UnknownVal, is UninitHost -> v
        WideHigh -> v
        is DvmObject -> (seen[v] as? DvmObject) ?: DvmObject(v.type).also { c ->
            seen[v] = c
            for ((k, fv) in v.fields) c.fields[k] = deepCopy(fv, seen)
        }
        is IntArray -> v.copyOf(); is LongArray -> v.copyOf(); is ByteArray -> v.copyOf()
        is CharArray -> v.copyOf(); is ShortArray -> v.copyOf(); is BooleanArray -> v.copyOf()
        is FloatArray -> v.copyOf(); is DoubleArray -> v.copyOf()
        is Array<*> -> (seen[v] as? Array<*>) ?: arrayOfNulls<Any?>(v.size).also { c ->
            seen[v] = c
            for (i in v.indices) c[i] = deepCopy(v[i], seen)
        }
        else -> throw Uncopyable(v)
    }
}
