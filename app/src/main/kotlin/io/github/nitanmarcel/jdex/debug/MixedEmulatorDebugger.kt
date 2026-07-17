package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.MixedNativeBridge
import io.github.nitanmarcel.jdex.disasm.NativeEmuState
import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.debug.EmuController
import io.github.nitanmarcel.jdex.exec.debug.EmuState
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import io.github.nitanmarcel.jdex.exec.runtime.WideHigh

class MixedEmulatorDebugger(
    private val source: MethodSource,
    soBytes: ByteArray,
    is64: Boolean,
    private val nativeId: String? = null,
    otherSoBytes: List<ByteArray> = emptyList(),
    private val world: io.github.nitanmarcel.jdex.exec.EmuWorld = io.github.nitanmarcel.jdex.exec.EmuWorld(),
    limits: ExecLimits = ExecLimits(maxMillis = 0),
) : DebuggerBase, AutoCloseable, HookableEmulator {

    private val bridge = MixedNativeBridge(source, soBytes, is64, otherSoBytes, world = world)
    private val nativeCtrl = bridge.controller
    private val dexCtrl = EmuController()
    private val vm = Vm(source, limits = limits, hook = dexCtrl, nativeBridge = bridge,
        hooks = world.hooks, android = world.android, ctx = world.ctx, statics = world.statics, androidEnvUnknown = false)
    private var listener: ((DebugState) -> Unit)? = null

    override fun addHook(descriptor: String, hook: io.github.nitanmarcel.jdex.exec.Interceptor): Int = world.hooks.add(descriptor, hook)
    override fun removeHook(id: Int): Boolean = world.hooks.remove(id)

    init {
        dexCtrl.onStop = { listener?.invoke(state) }
        nativeCtrl.onStop = { listener?.invoke(state) }
    }

    fun runMethod(descriptor: String, args: List<Any?> = emptyList(), receiver: Any? = null, pauseAtEntry: Boolean = true, runTo: Int? = null) {
        val m = source.method(descriptor.substringBefore("->"), descriptor.substringAfter("->")) ?: error("no such method: $descriptor")
        dexCtrl.start(vm, m, args, receiver, pauseAtEntry, runTo)
    }

    fun nativeSymbolAddress(name: String): Long? = nativeCtrl.symbolAddress(name)
    val nativeModuleBase: Long get() = nativeCtrl.moduleBase
    fun readMemory(address: Long, size: Int): ByteArray? = nativeCtrl.readMemory(address, size)
    fun writeMemory(address: Long, bytes: ByteArray): Boolean = nativeCtrl.writeMemory(address, bytes)
    fun returnValue(): Any? = dexCtrl.returnValue
    fun awaitFinished(timeoutMs: Long = 5000): Boolean = dexCtrl.awaitFinished(timeoutMs)
    fun awaitStop(timeoutMs: Long = 5000): Boolean = dexCtrl.awaitStop(timeoutMs) || nativeStopped()

    private fun nativeStopped() = nativeCtrl.state == NativeEmuState.STOPPED
    private fun nativeFrameCount() = if (nativeStopped()) (if ((nativeCtrl.registers()["lr"] ?: 0L) != 0L) 2 else 1) else 0

    override val state: DebugState
        get() = when {
            nativeStopped() -> nativeCtrl.pc().let { DebugState.Stopped(DebugLocation.Native(nativeId, it - nativeCtrl.moduleBase, it)) }
            dexCtrl.state == EmuState.STOPPED -> dexCtrl.top().let { DebugState.Stopped(DebugLocation.Dex(it?.descriptor ?: "", it?.pc ?: 0)) }
            dexCtrl.state == EmuState.RUNNING -> DebugState.Running
            else -> DebugState.Detached
        }

    override fun resume() = controlled { if (nativeStopped()) nativeCtrl.resume() else dexCtrl.resume() }
    override fun pause() { dexCtrl.pause() }
    override fun stepInto() = controlled {
        when {
            nativeStopped() -> nativeCtrl.stepInto()
            dexAtNativeInvoke() -> { nativeCtrl.armEntryStop(); dexCtrl.stepInto() }
            else -> dexCtrl.stepInto()
        }
    }

    private fun dexAtNativeInvoke(): Boolean {
        val ref = dexCtrl.top()?.insn?.ref as? MethodRef ?: return false
        return source.isNative(ref.declClass, ref.shortId)
    }
    override fun stepOver() = controlled { if (nativeStopped()) nativeCtrl.stepOver() else dexCtrl.stepOver() }
    override fun stepOut() = controlled { if (nativeStopped()) nativeCtrl.stepOut() else dexCtrl.stepOut() }
    override fun detach() = controlled { nativeCtrl.detach(); dexCtrl.detach() }

    override fun runToCursor(descriptor: String, dexPc: Int) = controlled { dexCtrl.runToCursor(descriptor, dexPc) }
    override fun runToCursorNative(nativeId: String, fileOffset: Long) {
        if ((this.nativeId == null || this.nativeId == nativeId) && nativeStopped())
            controlled { nativeCtrl.runToCursor(nativeCtrl.moduleBase + fileOffset) }
    }

    override fun addBreakpoint(bp: Breakpoint) {
        when (bp) {
            is Breakpoint.Dex -> dexCtrl.addBreakpoint(bp.methodDescriptor, bp.dexPc)
            is Breakpoint.Native -> if (nativeId == null || bp.nativeId == nativeId) nativeCtrl.addBreakpoint(nativeCtrl.moduleBase + bp.fileOffset)
        }
    }

    override fun removeBreakpoint(bp: Breakpoint) {
        when (bp) {
            is Breakpoint.Dex -> dexCtrl.removeBreakpoint(bp.methodDescriptor, bp.dexPc)
            is Breakpoint.Native -> if (nativeId == null || bp.nativeId == nativeId) nativeCtrl.removeBreakpoint(nativeCtrl.moduleBase + bp.fileOffset)
        }
    }

    override fun threads(): List<ThreadInfo> = listOf(ThreadInfo(1, "mixed-emulator", (if (nativeStopped()) "native" else dexCtrl.state.name.lowercase()), true))
    override fun currentThreadId(): Long = 1L

    override fun frames(threadId: Long): List<Frame> {
        val out = ArrayList<Frame>()
        val base = nativeCtrl.moduleBase
        if (nativeStopped()) {
            val regs = nativeCtrl.registers()
            val pc = regs["pc"] ?: 0L
            out.add(Frame(out.size, "0x${pc.toString(16)} (native)", DebugLocation.Native(nativeId, pc - base, pc)))
            val lr = regs["lr"] ?: 0L
            if (lr != 0L) out.add(Frame(out.size, "0x${lr.toString(16)} (native caller)", DebugLocation.Native(nativeId, lr - base, lr)))
        }
        dexCtrl.frames().forEach { f -> out.add(Frame(out.size, "${f.descriptor} @${f.pc}", DebugLocation.Dex(f.descriptor, f.pc))) }
        return out
    }

    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> {
        val nativeFrames = nativeFrameCount()
        if (frameIndex < nativeFrames) {
            if (frameIndex != 0) return emptyList()
            return nativeCtrl.registers().map { (name, v) ->
                DebugVar(name, "long", "0x${v.toString(16)}", editKey = "r:$name", editValue = v.toString())
            }
        }
        val dexIndex = frameIndex - nativeFrames
        val f = dexCtrl.frames().getOrNull(dexIndex) ?: return emptyList()
        return f.frame.regs.mapIndexed { i, v -> dexVar("v$i", v, "d:$dexIndex:$i") }
    }

    override fun setValue(editKey: String, text: String): Boolean {
        val parts = editKey.split(':')
        return when {
            parts.size == 2 && parts[0] == "r" -> {
                val t = text.trim()
                val value = runCatching { if (t.startsWith("0x")) t.substring(2).toLong(16) else t.toLong() }.getOrNull() ?: return false
                nativeCtrl.setRegister(parts[1], value)
            }
            parts.size == 4 && parts[0] == "d" -> {
                val frame = dexCtrl.frames().getOrNull(parts[1].toIntOrNull() ?: return false) ?: return false
                val reg = parts[2].toIntOrNull() ?: return false
                if (reg !in frame.frame.regs.indices) return false
                val parsed = parseDex(text, parts[3]) ?: return false
                frame.frame.set(reg, parsed); true
            }
            else -> false
        }
    }

    override fun children(ref: Long): List<DebugVar> = emptyList()

    override fun onStateChange(listener: (DebugState) -> Unit) { this.listener = listener }

    override fun close() = bridge.close()

    private fun controlled(action: () -> Unit) { action(); listener?.invoke(state) }

    private fun dexVar(name: String, v: Any?, editPrefix: String): DebugVar = when {
        v === WideHigh -> DebugVar(name, "", "(wide)", available = false)
        v == null -> DebugVar(name, "", "null")
        v is UnknownVal -> DebugVar(name, v.type ?: "?", "unknown", available = false)
        v is DvmObject -> DebugVar(name, v.type, v.type.removePrefix("L").removeSuffix(";").substringAfterLast('/'))
        v.javaClass.isArray -> DebugVar(name, "[", "${v.javaClass.simpleName}[${java.lang.reflect.Array.getLength(v)}]")
        v is String -> DebugVar(name, "Ljava/lang/String;", "\"$v\"", editKey = "$editPrefix:s", editValue = v)
        else -> DebugVar(name, v.javaClass.simpleName.lowercase(), v.toString(), editKey = "$editPrefix:${typeChar(v)}", editValue = v.toString())
    }

    private fun typeChar(v: Any?): String = when (v) {
        is Int -> "I"; is Long -> "J"; is Boolean -> "Z"; is Char -> "C"
        is Byte -> "B"; is Short -> "S"; is Float -> "F"; is Double -> "D"; else -> "I"
    }

    private fun parseDex(text: String, typeChar: String): Any? = runCatching {
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
