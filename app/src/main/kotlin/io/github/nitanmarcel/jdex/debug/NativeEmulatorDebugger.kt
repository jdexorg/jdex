package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.ElfArch
import io.github.nitanmarcel.jdex.disasm.JavaBridge
import io.github.nitanmarcel.jdex.disasm.KeystoneAssembler
import io.github.nitanmarcel.jdex.disasm.NativeEmuController
import io.github.nitanmarcel.jdex.disasm.NativeEmuState
import io.github.nitanmarcel.jdex.disasm.NativeHookContext
import io.github.nitanmarcel.jdex.disasm.NativeMemAccess

class NativeEmulatorDebugger(
    soBytes: ByteArray,
    private val is64: Boolean,
    private val nativeId: String? = null,
    bridge: JavaBridge? = null,
) : DebuggerBase, AutoCloseable {

    private val controller = NativeEmuController(soBytes, is64, bridge)
    private var listener: ((DebugState) -> Unit)? = null

    init { controller.onStop = { listener?.invoke(state) } }

    fun runMethod(className: String, methodSig: String, args: List<Any?> = emptyList(), pauseAtEntry: Boolean = true) {
        if (pauseAtEntry) controller.symbolAddress(methodSig.substringBefore('('))?.let { controller.addBreakpoint(it) }
        controller.callStatic(className, methodSig, *args.toTypedArray())
    }

    fun runFunction(fileOffset: Long, args: List<Any?> = emptyList(), pauseAtEntry: Boolean = true, runTo: Long? = null) {
        when {
            runTo != null -> controller.armRunTo(controller.moduleBase + runTo)
            pauseAtEntry -> controller.addBreakpoint(controller.moduleBase + fileOffset)
        }
        controller.callFunction(fileOffset, *args.toTypedArray())
    }

    override fun runToCursorNative(nativeId: String, fileOffset: Long) {
        if (this.nativeId == null || this.nativeId == nativeId) controlled { controller.runToCursor(controller.moduleBase + fileOffset) }
    }

    private val arch get() = if (is64) ElfArch.ARM64 else ElfArch.ARM

    val moduleBase: Long get() = controller.moduleBase
    fun symbolAddress(name: String): Long? = controller.symbolAddress(name)

    fun setRegister(name: String, value: Long): Boolean = controller.setRegister(name, value)
    fun writeMemory(address: Long, bytes: ByteArray): Boolean = controller.writeMemory(address, bytes)
    fun readMemory(address: Long, size: Int): ByteArray? = controller.readMemory(address, size)
    fun malloc(size: Int): Long = controller.malloc(size)
    fun regRead(name: String): Long? = controller.readRegister(name)
    fun callAddress(address: Long, args: List<Any?>): Any? = controller.callAddressBlocking(address, args)
    fun emulate(begin: Long, until: Long): Any? = controller.emulateBlocking(begin, until)

    fun patchNative(fileOffset: Long, asm: String): Boolean {
        val runtime = controller.moduleBase + fileOffset
        val bytes = KeystoneAssembler.assemble(asm, runtime, arch).getOrNull() ?: return false
        return controller.writeMemory(runtime, bytes)
    }
    fun onSyscall(cb: (NativeHookContext) -> Boolean) = controller.onSyscall(cb)
    fun clearSyscall() = controller.clearSyscall()
    fun hook(address: Long, onEnter: ((NativeHookContext) -> Unit)?, onLeave: ((NativeHookContext) -> Unit)?): Int = controller.hook(address, onEnter, onLeave)
    fun replace(address: Long, cb: (NativeHookContext) -> Long?): Int = controller.replace(address, cb)
    fun unhook(id: Int): Boolean = controller.unhook(id)
    fun memWatch(begin: Long, end: Long, onRead: ((NativeMemAccess) -> Unit)?, onWrite: ((NativeMemAccess) -> Unit)?): Int = controller.memWatch(begin, end, onRead, onWrite)
    fun trace(begin: Long, end: Long, cb: (NativeMemAccess) -> Unit): Int = controller.trace(begin, end, cb)
    fun modules(): List<Map<String, Any?>> = controller.modules()
    fun symbolAt(address: Long): Map<String, Any?>? = controller.symbolAt(address)

    fun returnValue(): Any? = controller.returnValue
    fun awaitStop(timeoutMs: Long = 5000): Boolean = controller.awaitStop(timeoutMs)
    fun awaitFinished(timeoutMs: Long = 5000): Boolean = controller.awaitFinished(timeoutMs)

    override val state: DebugState
        get() = when (controller.state) {
            NativeEmuState.RUNNING -> DebugState.Running
            NativeEmuState.STOPPED -> controller.pc().let { DebugState.Stopped(DebugLocation.Native(nativeId, it - controller.moduleBase, it)) }
            NativeEmuState.FINISHED, NativeEmuState.DETACHED -> DebugState.Detached
        }

    override fun resume() = controlled { controller.resume() }
    override fun pause() {}
    override fun stepInto() = controlled { controller.stepInto() }
    override fun stepOver() = controlled { controller.stepOver() }
    override fun stepOut() = controlled { controller.stepOut() }
    override fun detach() = controlled { controller.detach() }

    override fun addBreakpoint(bp: Breakpoint) {
        if (bp is Breakpoint.Native && (nativeId == null || bp.nativeId == nativeId)) controller.addBreakpoint(controller.moduleBase + bp.fileOffset)
    }

    override fun removeBreakpoint(bp: Breakpoint) {
        if (bp is Breakpoint.Native && (nativeId == null || bp.nativeId == nativeId)) controller.removeBreakpoint(controller.moduleBase + bp.fileOffset)
    }

    override fun threads(): List<ThreadInfo> = listOf(ThreadInfo(1, "native-emulator", controller.state.name.lowercase(), true))

    override fun currentThreadId(): Long = 1L

    override fun frames(threadId: Long): List<Frame> {
        if (controller.state != NativeEmuState.STOPPED) return emptyList()
        val regs = controller.registers()
        val base = controller.moduleBase
        val out = ArrayList<Frame>()
        val pc = regs["pc"] ?: 0L
        out.add(Frame(0, "0x${pc.toString(16)}", DebugLocation.Native(nativeId, pc - base, pc)))
        val lr = regs["lr"] ?: 0L
        if (lr != 0L) out.add(Frame(1, "0x${lr.toString(16)} (caller)", DebugLocation.Native(nativeId, lr - base, lr)))
        return out
    }

    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> {
        if (frameIndex != 0 || controller.state != NativeEmuState.STOPPED) return emptyList()
        return controller.registers().map { (name, v) ->
            DebugVar(name, "long", "0x${v.toString(16)}", editKey = "r:$name", editValue = v.toString())
        }
    }

    override fun setValue(editKey: String, text: String): Boolean {
        val parts = editKey.split(':')
        if (parts.size != 2 || parts[0] != "r") return false
        val t = text.trim()
        val value = runCatching { if (t.startsWith("0x")) t.substring(2).toLong(16) else t.toLong() }.getOrNull() ?: return false
        return controller.setRegister(parts[1], value)
    }

    override fun children(ref: Long): List<DebugVar> = emptyList()

    override fun onStateChange(listener: (DebugState) -> Unit) { this.listener = listener }

    override fun close() = controller.close()

    private fun controlled(action: () -> Unit) { action(); listener?.invoke(state) }
}
