package io.github.nitanmarcel.jdex.exec

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HookCall(val method: String, val receiver: Any?, val args: MutableList<Any?>) {
    var replaced: Boolean = false
        private set
    var result: Any? = null
        private set

    fun setArg(index: Int, value: Any?) { if (index in args.indices) args[index] = value }
    fun replace(value: Any?) { replaced = true; result = value }
}

fun interface Interceptor { fun onInvoke(call: HookCall) }

class HookRegistry {
    private val counter = AtomicInteger(0)
    private val byDescriptor = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<Int, Interceptor>>>()
    private val idToDescriptor = ConcurrentHashMap<Int, String>()

    fun add(descriptor: String, hook: Interceptor): Int {
        val id = counter.incrementAndGet()
        byDescriptor.getOrPut(descriptor) { CopyOnWriteArrayList() }.add(id to hook)
        idToDescriptor[id] = descriptor
        return id
    }

    fun remove(id: Int): Boolean {
        val descriptor = idToDescriptor.remove(id) ?: return false
        byDescriptor[descriptor]?.removeIf { it.first == id }
        return true
    }

    fun interceptors(descriptor: String): List<Interceptor> =
        byDescriptor[descriptor]?.map { it.second } ?: emptyList()

    fun list(): List<Pair<Int, String>> = idToDescriptor.entries.map { it.key to it.value }

    fun clear() {
        byDescriptor.clear()
        idToDescriptor.clear()
    }

    fun isEmpty(): Boolean = idToDescriptor.isEmpty()
}
