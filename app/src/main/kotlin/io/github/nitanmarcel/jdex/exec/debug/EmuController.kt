package io.github.nitanmarcel.jdex.exec.debug

import io.github.nitanmarcel.jdex.exec.ExecHook
import io.github.nitanmarcel.jdex.exec.Frame
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.VmAbort
import io.github.nitanmarcel.jdex.exec.model.DalvikInsn
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class EmuState { RUNNING, STOPPED, FINISHED, DETACHED }

class EmuFrame(val method: DexMethod, val frame: Frame) {
    var pc: Int = 0
    var insn: DalvikInsn? = null
    val descriptor: String get() = "${method.declClass}->${method.ref.shortId}"
}

class EmuController : ExecHook {

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val stack = ArrayDeque<EmuFrame>()
    private val breakpoints = HashSet<String>()
    private var oneShot: String? = null

    private enum class Mode { RUN, INTO, OVER, OUT }
    private var mode = Mode.RUN
    private var baseDepth = 0
    private var entryTarget: String? = null

    @Volatile
    var state: EmuState = EmuState.RUNNING
        private set

    @Volatile
    var returnValue: Any? = null
        private set

    var onStop: (() -> Unit)? = null

    private fun bpKey(descriptor: String, pc: Int) = "$descriptor@$pc"

    fun addBreakpoint(descriptor: String, dexPc: Int) = lock.withLock { breakpoints.add(bpKey(descriptor, dexPc)) }
    fun removeBreakpoint(descriptor: String, dexPc: Int) = lock.withLock { breakpoints.remove(bpKey(descriptor, dexPc)) }

    fun frames(): List<EmuFrame> = lock.withLock { stack.toList().asReversed() }
    fun top(): EmuFrame? = lock.withLock { stack.lastOrNull() }

    fun start(vm: Vm, method: DexMethod, args: List<Any?> = emptyList(), receiver: Any? = null, pauseAtEntry: Boolean = true, runTo: Int? = null) {
        require(vm.hook === this) { "vm must be constructed with this controller as its hook" }
        lock.withLock {
            stack.clear()
            mode = Mode.RUN
            baseDepth = 0
            val descriptor = "${method.declClass}->${method.ref.shortId}"
            oneShot = runTo?.let { bpKey(descriptor, it) }
            entryTarget = if (pauseAtEntry && runTo == null) descriptor else null
            state = EmuState.RUNNING
            returnValue = null
        }
        Thread({
            val r = runCatching { vm.invoke(method, args, receiver) }.getOrNull()
            lock.withLock {
                if (state != EmuState.DETACHED) { returnValue = r; state = EmuState.FINISHED }
                cond.signalAll()
            }
            onStop?.invoke()
        }, "jdex-emu").apply { isDaemon = true; start() }
    }

    override fun onEnter(method: DexMethod, frame: Frame) = lock.withLock { stack.addLast(EmuFrame(method, frame)) }

    override fun onExit(method: DexMethod) { lock.withLock { stack.removeLastOrNull() } }

    override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
        lock.withLock {
            val top = stack.lastOrNull() ?: return
            top.pc = insn.offset; top.insn = insn
            if (state == EmuState.DETACHED) throw VmAbort("detached")
            if (!shouldStop(top, insn.offset)) return
            state = EmuState.STOPPED
            mode = Mode.RUN
            oneShot = null
            cond.signalAll()
        }
        onStop?.invoke()
        lock.withLock {
            while (state == EmuState.STOPPED) cond.await()
            if (state == EmuState.DETACHED) throw VmAbort("detached")
        }
    }

    private fun shouldStop(top: EmuFrame, pc: Int): Boolean {
        val key = bpKey(top.descriptor, pc)
        if (key in breakpoints || oneShot == key) return true
        entryTarget?.let { return if (top.descriptor == it) { entryTarget = null; true } else false }
        return when (mode) {
            Mode.INTO -> true
            Mode.OVER -> stack.size <= baseDepth
            Mode.OUT -> stack.size < baseDepth
            Mode.RUN -> false
        }
    }

    fun resume() = signal(Mode.RUN)
    fun stepInto() = signal(Mode.INTO)
    fun stepOver() = signal(Mode.OVER)
    fun stepOut() = signal(Mode.OUT)

    fun runToCursor(descriptor: String, dexPc: Int) {
        lock.withLock { if (state == EmuState.STOPPED) { oneShot = bpKey(descriptor, dexPc); resumeLocked(Mode.RUN) } }
    }

    fun pause() = lock.withLock { if (state == EmuState.RUNNING) mode = Mode.INTO }

    fun detach() = lock.withLock { state = EmuState.DETACHED; cond.signalAll() }

    private fun signal(m: Mode) = lock.withLock { if (state == EmuState.STOPPED) resumeLocked(m) }

    private fun resumeLocked(m: Mode) {
        baseDepth = stack.size
        mode = m
        state = EmuState.RUNNING
        cond.signalAll()
    }

    fun awaitStop(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var remaining = timeoutMs * 1_000_000
        while (state == EmuState.RUNNING && remaining > 0) remaining = cond.awaitNanos(remaining)
        state != EmuState.RUNNING
    }

    fun awaitFinished(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var remaining = timeoutMs * 1_000_000
        while (state != EmuState.FINISHED && state != EmuState.DETACHED && remaining > 0) remaining = cond.awaitNanos(remaining)
        state == EmuState.FINISHED
    }
}
