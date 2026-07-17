package io.github.nitanmarcel.jdex.disasm

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class NativeEmuState { RUNNING, STOPPED, FINISHED, DETACHED }

class NativeEmuController(soBytes: ByteArray, is64: Boolean, bridge: JavaBridge? = null) : AutoCloseable {

    private val emu = NativeEmulator(soBytes, is64, bridge = bridge, timeoutMs = 0)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val breakpoints = HashSet<Long>()

    private enum class Step { NONE, INTO, OVER, OUT }

    @Volatile private var armed = false
    private var stepMode = Step.NONE
    private var entryStop = false
    private var oneShot: Long? = null
    private var pendingRunTo: Long? = null
    private var overTarget = 0L
    private var baseSp = 0L
    private var stoppedPc = 0L
    private var stoppedSize = 0
    private var snapshot: Map<String, Long> = emptyMap()
    private val pendingRegs = LinkedHashMap<String, Long>()
    private val pendingMem = ArrayList<Pair<Long, ByteArray>>()

    @Volatile
    var state = NativeEmuState.FINISHED
        private set

    @Volatile
    var returnValue: Any? = null
        private set

    var onStop: (() -> Unit)? = null

    init { emu.installCodeHook(::onStep) }

    val moduleBase: Long get() = emu.moduleBase
    fun symbolAddress(name: String): Long? = emu.symbolAddress(name)
    fun malloc(size: Int): Long = lock.withLock { if (state == NativeEmuState.RUNNING) 0L else emu.malloc(size) }
    fun readRegister(name: String): Long? = lock.withLock {
        when (state) {
            NativeEmuState.RUNNING -> null
            NativeEmuState.STOPPED -> snapshot[name] ?: emu.readRegister(name)
            else -> emu.readRegister(name)
        }
    }
    fun addBreakpoint(address: Long) = lock.withLock { breakpoints.add(address); Unit }
    fun removeBreakpoint(address: Long) = lock.withLock { breakpoints.remove(address); Unit }
    fun registers(): Map<String, Long> = lock.withLock { snapshot }
    fun pc(): Long = lock.withLock { snapshot["pc"] ?: 0L }

    fun callStatic(className: String, methodSig: String, vararg args: Any?) =
        launch { emu.callStaticString(className, methodSig, *args) }

    fun callFunction(offset: Long, vararg args: Any?) =
        launch { emu.callFunction(offset, *args) }

    fun callAddressBlocking(address: Long, args: List<Any?>): Any? {
        launch { emu.callAddress(address, *args.toTypedArray()) }
        awaitStop(Long.MAX_VALUE / 2_000_000)
        return if (state == NativeEmuState.FINISHED) returnValue else null
    }

    fun emulateBlocking(begin: Long, until: Long): Any? {
        launch { emu.emulate(begin, until) }
        awaitStop(Long.MAX_VALUE / 2_000_000)
        return if (state == NativeEmuState.FINISHED) returnValue else null
    }

    fun armEntryStop() = lock.withLock { entryStop = true }

    private fun beginRun() = lock.withLock {
        state = NativeEmuState.RUNNING; returnValue = null; armed = true
        stepMode = if (entryStop) Step.INTO else Step.NONE
        entryStop = false
        oneShot = pendingRunTo; pendingRunTo = null
    }

    private fun launch(body: () -> Any?) {
        beginRun()
        Thread({
            val r = runCatching(body).getOrNull()
            lock.withLock {
                armed = false
                if (state != NativeEmuState.DETACHED) { returnValue = r; state = NativeEmuState.FINISHED }
                cond.signalAll()
            }
            onStop?.invoke()
        }, "jdex-native-emu").apply { isDaemon = true; start() }
    }

    fun callSync(className: String, methodSig: String, receiver: Any?, args: List<Any?>): String? {
        beginRun()
        val r = runCatching { emu.callString(className, methodSig, receiver, args) }.getOrNull()
        lock.withLock {
            armed = false
            if (state != NativeEmuState.DETACHED) { returnValue = r; state = NativeEmuState.FINISHED }
            cond.signalAll()
        }
        onStop?.invoke()
        return r
    }

    private fun onStep(address: Long, size: Int) {
        if (!armed) return
        if ((fnHooks.isNotEmpty() || leaveWatch.isNotEmpty()) && runFnHooks(address)) return
        lock.withLock {
            if (state == NativeEmuState.DETACHED) { emu.stopEmulation(); return }
            if (!shouldStop(address)) return
            stoppedPc = address
            stoppedSize = size
            snapshot = emu.snapshotRegisters(address)
            stepMode = Step.NONE
            oneShot = null
            state = NativeEmuState.STOPPED
            cond.signalAll()
        }
        onStop?.invoke()
        lock.withLock {
            while (state == NativeEmuState.STOPPED) cond.await()
            if (state == NativeEmuState.DETACHED) { emu.stopEmulation(); return }
            pendingRegs.forEach { (n, v) -> emu.writeRegister(n, v) }
            pendingMem.forEach { (a, b) -> emu.writeMemory(a, b) }
            pendingRegs.clear(); pendingMem.clear()
        }
    }

    fun setRegister(name: String, value: Long): Boolean = lock.withLock {
        when (state) {
            NativeEmuState.RUNNING -> false
            NativeEmuState.STOPPED -> { pendingRegs[name] = value; snapshot = LinkedHashMap(snapshot).apply { put(name, value) }; true }
            else -> emu.writeRegister(name, value)
        }
    }

    fun writeMemory(address: Long, bytes: ByteArray): Boolean = lock.withLock {
        when (state) {
            NativeEmuState.RUNNING -> false
            NativeEmuState.STOPPED -> { pendingMem.add(address to bytes.copyOf()); true }
            else -> emu.writeMemory(address, bytes)
        }
    }

    fun readMemory(address: Long, size: Int): ByteArray? = lock.withLock {
        if (state == NativeEmuState.RUNNING) null else emu.readMemory(address, size)
    }

    private fun shouldStop(address: Long): Boolean = address in breakpoints || address == oneShot || when (stepMode) {
        Step.INTO -> true
        Step.OVER -> address == overTarget || emu.currentSp() > baseSp
        Step.OUT -> emu.currentSp() > baseSp
        Step.NONE -> false
    }

    fun armRunTo(address: Long) = lock.withLock { pendingRunTo = address }

    fun runToCursor(address: Long) = signal { oneShot = address; stepMode = Step.NONE }

    fun resume() = signal { stepMode = Step.NONE }
    fun stepInto() = signal { stepMode = Step.INTO }
    fun stepOver() = signal { stepMode = Step.OVER; overTarget = stoppedPc + stoppedSize; baseSp = currentBaseSp() }
    fun stepOut() = signal { stepMode = Step.OUT; baseSp = currentBaseSp() }
    fun detach() = lock.withLock { state = NativeEmuState.DETACHED; cond.signalAll() }

    private fun currentBaseSp(): Long = snapshot["sp"] ?: 0L

    private fun signal(prepare: () -> Unit) = lock.withLock {
        if (state == NativeEmuState.STOPPED) { prepare(); state = NativeEmuState.RUNNING; cond.signalAll() }
    }

    fun awaitStop(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var rem = timeoutMs * 1_000_000
        while (state == NativeEmuState.RUNNING && rem > 0) rem = cond.awaitNanos(rem)
        state != NativeEmuState.RUNNING
    }

    fun awaitFinished(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var rem = timeoutMs * 1_000_000
        while (state != NativeEmuState.FINISHED && state != NativeEmuState.DETACHED && rem > 0) rem = cond.awaitNanos(rem)
        state == NativeEmuState.FINISHED
    }

    private class FnHook(val id: Int, val address: Long, val onEnter: ((NativeHookContext) -> Unit)?, val onLeave: ((NativeHookContext) -> Unit)?, val replace: ((NativeHookContext) -> Long?)?)

    private val fnHooks = java.util.concurrent.ConcurrentHashMap<Long, FnHook>()
    private val hooksById = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val leaveWatch = java.util.concurrent.ConcurrentHashMap<Long, ArrayDeque<(NativeHookContext) -> Unit>>()
    private val backendHooks = java.util.concurrent.ConcurrentHashMap<Int, () -> Unit>()
    private val nextHookId = AtomicInteger(1)

    fun onSyscall(cb: (NativeHookContext) -> Boolean) = emu.syscallInterceptor(cb)
    fun clearSyscall() = emu.setSyscallInterceptor(null)

    fun memWatch(begin: Long, end: Long, onRead: ((NativeMemAccess) -> Unit)?, onWrite: ((NativeMemAccess) -> Unit)?): Int {
        if (onRead == null && onWrite == null) return -1
        if (state == NativeEmuState.RUNNING) return -1
        val id = nextHookId.getAndIncrement()
        val removers = ArrayList<() -> Unit>(2)
        onRead?.let { removers.add(emu.installReadHook(begin, end, it)) }
        onWrite?.let { removers.add(emu.installWriteHook(begin, end, it)) }
        backendHooks[id] = { removers.forEach { it() } }
        return id
    }

    fun trace(begin: Long, end: Long, cb: (NativeMemAccess) -> Unit): Int {
        if (state == NativeEmuState.RUNNING) return -1
        val id = nextHookId.getAndIncrement()
        backendHooks[id] = emu.installBlockHook(begin, end, cb)
        return id
    }

    fun modules(): List<Map<String, Any?>> = emu.loadedModules()
    fun symbolAt(address: Long): Map<String, Any?>? = emu.symbolAt(address)

    fun hook(address: Long, onEnter: ((NativeHookContext) -> Unit)?, onLeave: ((NativeHookContext) -> Unit)?): Int {
        val id = nextHookId.getAndIncrement()
        fnHooks[address] = FnHook(id, address, onEnter, onLeave, null)
        hooksById[id] = address
        return id
    }

    fun replace(address: Long, cb: (NativeHookContext) -> Long?): Int {
        val id = nextHookId.getAndIncrement()
        fnHooks[address] = FnHook(id, address, null, null, cb)
        hooksById[id] = address
        return id
    }

    fun unhook(id: Int): Boolean {
        backendHooks.remove(id)?.let { it(); return true }
        val addr = hooksById.remove(id) ?: return false
        return fnHooks.remove(addr) != null
    }

    private fun runFnHooks(address: Long): Boolean {
        leaveWatch[address]?.let { stack ->
            val cb = synchronized(stack) { stack.removeLastOrNull().also { if (stack.isEmpty()) leaveWatch.remove(address) } }
            cb?.let { runCatching { it(emu.hookContext()) } }
        }
        val h = fnHooks[address] ?: return false
        if (h.replace != null) {
            val ret = runCatching { h.replace.invoke(emu.hookContext()) }.getOrNull()
            if (ret != null) {
                emu.hookContext().setRet(ret)
                emu.readRegister("lr")?.let { emu.writeRegister("pc", it) }
                return true
            }
            return false
        }
        h.onEnter?.let { runCatching { it(emu.hookContext()) } }
        h.onLeave?.let { leave ->
            val lr = emu.readRegister("lr") ?: return false
            leaveWatch.getOrPut(lr) { ArrayDeque() }.let { synchronized(it) { it.addLast(leave) } }
        }
        return false
    }

    fun awaitStopped(timeoutMs: Long = 5000): Boolean = lock.withLock {
        var rem = timeoutMs * 1_000_000
        while (state != NativeEmuState.STOPPED && state != NativeEmuState.DETACHED && rem > 0) rem = cond.awaitNanos(rem)
        state == NativeEmuState.STOPPED
    }

    override fun close() = emu.close()
}
