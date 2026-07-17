package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.StubHandler
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.debug.EmuController
import io.github.nitanmarcel.jdex.exec.debug.EmuState
import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import io.github.nitanmarcel.jdex.exec.runtime.WideHigh
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EmulatorDebugger(
    private val source: MethodSource,
    private val limits: ExecLimits = ExecLimits(maxMillis = 0),
    private val nativeBridge: io.github.nitanmarcel.jdex.exec.NativeBridge? = null,
    private val world: io.github.nitanmarcel.jdex.exec.EmuWorld = io.github.nitanmarcel.jdex.exec.EmuWorld(),
) : DebuggerBase, HookableEmulator {

    private val controller = EmuController()
    private val vm = Vm(source, limits = limits, hook = controller, nativeBridge = nativeBridge,
        hooks = world.hooks, android = world.android, ctx = world.ctx, statics = world.statics, androidEnvUnknown = false)
    private var listener: ((DebugState) -> Unit)? = null

    override fun addHook(descriptor: String, hook: io.github.nitanmarcel.jdex.exec.Interceptor): Int = world.hooks.add(descriptor, hook)
    override fun removeHook(id: Int): Boolean = world.hooks.remove(id)

    private val refs = ConcurrentHashMap<Long, Any>()
    private val refCounter = AtomicLong(1)

    init { controller.onStop = { listener?.invoke(state) } }

    fun runMethod(descriptor: String, args: List<Any?> = emptyList(), receiver: Any? = null, pauseAtEntry: Boolean = true, runTo: Int? = null) {
        val m = methodOf(descriptor) ?: error("no such method: $descriptor")
        controller.start(vm, m, args, receiver, pauseAtEntry, runTo)
    }

    fun resolve(descriptor: String, args: List<Any?>? = null): DataflowResult {
        val m = methodOf(descriptor) ?: error("no such method: $descriptor")
        return Dataflow(Vm(source, limits = limits, androidEnvUnknown = false)).analyze(m, args)
    }

    fun registerStub(classDesc: String, name: String, handler: StubHandler) = vm.android.registerMethod(classDesc, name, handler)

    fun registerField(classDesc: String, name: String, value: Any?) = vm.android.registerField(classDesc, name, value)

    fun returnValue(): Any? = controller.returnValue

    fun awaitStop(timeoutMs: Long = 5000): Boolean = controller.awaitStop(timeoutMs)

    fun awaitFinished(timeoutMs: Long = 5000): Boolean = controller.awaitFinished(timeoutMs)

    fun setRegister(frameIndex: Int, reg: Int, value: Any?): Boolean {
        val f = controller.frames().getOrNull(frameIndex) ?: return false
        if (reg !in f.frame.regs.indices) return false
        f.frame.set(reg, value)
        return true
    }

    override val state: DebugState
        get() = when (controller.state) {
            EmuState.RUNNING -> DebugState.Running
            EmuState.STOPPED -> {
                val t = controller.top()
                DebugState.Stopped(DebugLocation.Dex(t?.descriptor ?: "", t?.pc ?: 0))
            }
            EmuState.FINISHED, EmuState.DETACHED -> DebugState.Detached
        }

    override fun resume() = controlled { controller.resume() }
    override fun pause() = controller.pause()
    override fun stepInto() = controlled { controller.stepInto() }
    override fun stepOver() = controlled { controller.stepOver() }
    override fun stepOut() = controlled { controller.stepOut() }
    override fun detach() = controlled { controller.detach() }

    override fun addBreakpoint(bp: Breakpoint) {
        if (bp is Breakpoint.Dex) controller.addBreakpoint(bp.methodDescriptor, bp.dexPc)
    }

    override fun removeBreakpoint(bp: Breakpoint) {
        if (bp is Breakpoint.Dex) controller.removeBreakpoint(bp.methodDescriptor, bp.dexPc)
    }

    override fun runToCursor(descriptor: String, dexPc: Int) = controlled { controller.runToCursor(descriptor, dexPc) }

    override fun threads(): List<ThreadInfo> = listOf(ThreadInfo(1, "emulator", controller.state.name.lowercase(), true))

    override fun currentThreadId(): Long = 1L

    override fun frames(threadId: Long): List<Frame> =
        controller.frames().mapIndexed { i, f -> Frame(i, "${f.descriptor} @${f.pc}", DebugLocation.Dex(f.descriptor, f.pc)) }

    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> {
        val f = controller.frames().getOrNull(frameIndex) ?: return emptyList()
        return f.frame.regs.mapIndexed { i, v -> varOf("v$i", v, "r:$frameIndex:$i") }
    }

    override fun children(ref: Long): List<DebugVar> {
        return when (val o = refs[ref]) {
            is DvmObject -> o.fields.entries.map { (k, v) -> varOf(k.substringAfterLast('.'), v, null) }
            null -> emptyList()
            else -> if (o.javaClass.isArray) arrayChildren(o) else emptyList()
        }
    }

    override fun setValue(editKey: String, text: String): Boolean {
        val parts = editKey.split(':')
        if (parts.size == 4 && parts[0] == "r") {
            val frameIndex = parts[1].toIntOrNull() ?: return false
            val reg = parts[2].toIntOrNull() ?: return false
            val parsed = parse(text, parts[3]) ?: return false
            return setRegister(frameIndex, reg, parsed)
        }
        return false
    }

    override fun onStateChange(listener: (DebugState) -> Unit) { this.listener = listener }

    private fun controlled(action: () -> Unit) {
        refs.clear()
        action()
        listener?.invoke(state)
    }

    private fun methodOf(descriptor: String) =
        descriptor.substringBefore("->").let { cls -> source.method(cls, descriptor.substringAfter("->")) }

    private fun varOf(name: String, v: Any?, editPrefix: String?): DebugVar = when {
        v === WideHigh -> DebugVar(name, "", "(wide)", available = false)
        v == null -> DebugVar(name, "", "null")
        v is UnknownVal -> DebugVar(name, v.type ?: "?", "unknown", available = false)
        v is DvmObject -> DebugVar(name, v.type, simple(v.type), ref = mint(v))
        v.javaClass.isArray -> DebugVar(name, arrType(v), "${arrType(v)}[${java.lang.reflect.Array.getLength(v)}]", ref = mint(v))
        v is String -> DebugVar(name, "Ljava/lang/String;", "\"$v\"", editKey = editPrefix?.let { "$it:s" })
        else -> DebugVar(name, primType(v), render(v), editKey = editPrefix?.let { "$it:${typeChar(v)}" })
    }

    private fun arrayChildren(a: Any): List<DebugVar> {
        val n = java.lang.reflect.Array.getLength(a)
        val cap = minOf(n, 200)
        val out = ArrayList<DebugVar>(cap)
        for (i in 0 until cap) out.add(varOf("[$i]", java.lang.reflect.Array.get(a, i), null))
        if (n > cap) out.add(DebugVar("…", "", "${n - cap} more", available = false))
        return out
    }

    private fun mint(v: Any): Long = refCounter.getAndIncrement().also { refs[it] = v }

    private fun render(v: Any?): String = when (v) {
        is Int -> "$v"
        is Long -> "${v}L"
        is Boolean, is Char -> "$v"
        is Byte, is Short -> "$v"
        is Float -> "$v"
        is Double -> "$v"
        else -> v.toString()
    }

    private fun primType(v: Any?): String = when (v) {
        is Int -> "int"; is Long -> "long"; is Boolean -> "boolean"; is Char -> "char"
        is Byte -> "byte"; is Short -> "short"; is Float -> "float"; is Double -> "double"
        else -> "?"
    }

    private fun typeChar(v: Any?): String = when (v) {
        is Int -> "I"; is Long -> "J"; is Boolean -> "Z"; is Char -> "C"
        is Byte -> "B"; is Short -> "S"; is Float -> "F"; is Double -> "D"
        else -> "I"
    }

    private fun arrType(v: Any): String = when (v) {
        is IntArray -> "[I"; is LongArray -> "[J"; is ByteArray -> "[B"; is CharArray -> "[C"
        is ShortArray -> "[S"; is BooleanArray -> "[Z"; is FloatArray -> "[F"; is DoubleArray -> "[D"
        else -> "[Ljava/lang/Object;"
    }

    private fun simple(type: String) = type.removePrefix("L").removeSuffix(";").substringAfterLast('/')

    private fun parse(text: String, typeChar: String): Any? = runCatching {
        val t = text.trim()
        fun int() = if (t.startsWith("0x")) t.substring(2).toLong(16) else t.toLong()
        when (typeChar) {
            "I" -> int().toInt(); "J" -> int(); "B" -> int().toByte(); "S" -> int().toShort()
            "C" -> if (t.length == 1) t[0] else int().toInt().toChar()
            "Z" -> t.toBoolean(); "F" -> t.toFloat(); "D" -> t.toDouble()
            "s" -> t
            else -> null
        }
    }.getOrNull()
}
