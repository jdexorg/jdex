package io.github.nitanmarcel.jdex.debug

import java.util.concurrent.Executors

class MixedSession private constructor(
    private val art: ArtSession,
    private val native: NativeSession,
    private val bindingMap: BindingMap,
) : DebugSession {

    override val device: DebugDevice get() = art.device
    override val pid: Int get() = art.pid

    private val crossings = CrossingStack(Owner.DEX)
    private val coord = Executors.newSingleThreadExecutor { Thread(it, "jdex-mixed-coord").apply { isDaemon = true } }
    @Volatile var log: (String) -> Unit = {}
    @Volatile private var current: DebugState = DebugState.Running
    @Volatile private var stateListener: (DebugState) -> Unit = {}
    private val armedEntries = HashMap<Long, Pair<String, Long>>()
    private val callSiteEntries = HashMap<Long, Pair<String, Long>>()
    private val deferredNative = ArrayList<Breakpoint.Native>()
    @Volatile private var steppingOver = false
    private val pendingDex = ArrayList<Breakpoint.Dex>()
    @Volatile private var rendezvousAddr: Long? = null

    fun owner(): Owner = crossings.owner

    private fun resolvablePending(): List<Triple<Long, String, Long>> =
        bindingMap.allEntries().mapNotNull { (id, vaddr) ->
            native.runtimeAddr(id, vaddr)?.takeIf { it !in armedEntries }?.let { Triple(it, id, vaddr) }
        }

    private fun armResolvableEntries() {
        for ((addr, id, vaddr) in resolvablePending()) { native.armRaw(addr); armedEntries[addr] = id to vaddr }
    }

    private fun armNativeEntries() {
        if (resolvablePending().isEmpty()) return
        native.interruptForArming()
        armResolvableEntries()
        native.resumeSilently()
    }

    private fun onRendezvous() {
        try {
            val bindingWork = armedEntries.size < bindingMap.allEntries().size
            if (deferredNative.isEmpty() && !bindingWork) return
            if (native.rendezvousState() != 0) return
            native.refreshLinkMap()
            val wanted = (deferredNative.map { it.nativeId } + bindingMap.allEntries().map { it.first }).toSet()
            if (wanted.none { native.libBase(it) != null }) return
            native.refreshModules()
            armResolvableEntries()
            installDeferred()
            log("native breakpoints armed on library load")
        } catch (t: Throwable) {
            log("rendezvous error: $t")
        } finally {
            runCatching { native.resume() }
        }
    }

    private fun installDeferred() {
        val it = deferredNative.iterator()
        while (it.hasNext()) {
            val bp = it.next()
            if (native.runtimeAddr(bp.nativeId, bp.fileOffset) != null) { native.addBreakpoint(bp); it.remove() }
        }
    }

    private fun retryDeferredNativeOnDexStop() {
        if (deferredNative.isEmpty() && bindingMap.allEntries().none { native.runtimeAddr(it.first, it.second) == null }) return
        coord.submit { native.refreshModules(); armNativeEntries(); installDeferred() }
    }

    private fun armCallSites(nativeId: String) {
        val sites = bindingMap.callSites(nativeId).mapNotNull { vaddr ->
            native.runtimeAddr(nativeId, vaddr)?.takeIf { it !in callSiteEntries }?.let { Triple(it, nativeId, vaddr) }
        }
        for ((addr, id, vaddr) in sites) { native.armRaw(addr); callSiteEntries[addr] = id to vaddr }
    }

    private fun onRawHit(addr: Long, regs: Map<String, ULong>) {
        if (addr == rendezvousAddr) { onRendezvous(); return }
        if (steppingOver && addr in armedEntries) { steppingOver = false; native.resume(); return }
        val pc = addr
        armedEntries[addr]?.let { (id, vaddr) ->
            ownerStop(Owner.NATIVE)
            armCallSites(id)
            publish(DebugState.Stopped(DebugLocation.Native(id, vaddr, pc)))
            return
        }
        callSiteEntries[addr]?.let { (id, vaddr) ->
            ownerStop(Owner.NATIVE)
            publish(DebugState.Stopped(DebugLocation.Native(id, vaddr, pc)))
        }
    }

    override val state: DebugState get() = current

    override fun onStateChange(listener: (DebugState) -> Unit) { stateListener = listener }

    private fun publish(s: DebugState) { current = s; stateListener(s) }

    private fun active(): DebugSession = if (crossings.owner == Owner.DEX) art else native

    override fun resume() = active().resume()
    override fun pause() = active().pause()
    override fun stepInto() = active().stepInto()
    override fun stepOver() {
        if (crossings.owner == Owner.DEX) { steppingOver = true; art.stepOver() } else native.stepOver()
    }
    override fun stepOut() = active().stepOut()

    override fun addBreakpoint(bp: Breakpoint) {
        when (bp) {
            is Breakpoint.Dex -> coord.submit { if (crossings.owner == Owner.DEX) art.addBreakpoint(bp) else pendingDex.add(bp) }
            is Breakpoint.Native -> coord.submit {
                if (native.runtimeAddr(bp.nativeId, bp.fileOffset) != null) native.addBreakpoint(bp)
                else deferredNative.add(bp)
            }
        }
    }
    override fun removeBreakpoint(bp: Breakpoint) {
        when (bp) {
            is Breakpoint.Dex -> coord.submit { pendingDex.remove(bp); if (crossings.owner == Owner.DEX) art.removeBreakpoint(bp) }
            is Breakpoint.Native -> coord.submit { deferredNative.remove(bp); native.removeBreakpoint(bp) }
        }
    }

    private fun flushPendingDex() {
        if (pendingDex.isEmpty()) return
        val copy = pendingDex.toList(); pendingDex.clear()
        copy.forEach { runCatching { art.addBreakpoint(it) } }
    }

    override fun setExceptionBreak(caught: Boolean, uncaught: Boolean) = art.setExceptionBreak(caught, uncaught)
    override fun runToCursor(descriptor: String, dexPc: Int) = art.runToCursor(descriptor, dexPc)
    override fun runToCursorNative(nativeId: String, fileOffset: Long) {
        if (crossings.owner == Owner.NATIVE) native.runToCursorNative(nativeId, fileOffset)
    }

    override fun threads(): List<ThreadInfo> = active().threads()
    override fun currentThreadId(): Long = active().currentThreadId()
    override fun frames(threadId: Long): List<Frame> = active().frames(threadId)
    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> = active().variables(threadId, frameIndex)
    override fun children(ref: Long): List<DebugVar> = active().children(ref)
    override fun setValue(editKey: String, text: String): Boolean = active().setValue(editKey, text)
    override fun readMemory(address: Long, length: Int): ByteArray? = native.readMemory(address, length)
    override fun writeMemory(address: Long, bytes: ByteArray): Boolean = native.writeMemory(address, bytes)
    override fun runtimeAddr(nativeId: String, vaddr: Long): Long? = native.runtimeAddr(nativeId, vaddr)
    override fun modules(): List<LoadedModule> = native.modules()
    override fun inlineValues(): Map<String, String> = active().inlineValues()

    private fun ownerStop(side: Owner) {
        if (crossings.owner == side) return
        val top = crossings.peek()
        if (top != null && top.from == side) crossings.back() else crossings.cross(side, "")
    }

    private fun onArtState(s: DebugState) {
        when (s) {
            is DebugState.Stopped -> { steppingOver = false; ownerStop(Owner.DEX); flushPendingDex(); retryDeferredNativeOnDexStop(); publish(s) }
            DebugState.Running -> if (crossings.owner == Owner.DEX) publish(s)
            DebugState.Detached -> publish(s)
        }
    }

    private fun onNativeState(s: DebugState) {
        when (s) {
            is DebugState.Stopped -> { ownerStop(Owner.NATIVE); publish(s) }
            DebugState.Running -> if (crossings.owner == Owner.NATIVE) publish(s)
            DebugState.Detached -> publish(s)
        }
    }

    override fun detach() {
        runCatching { native.detach() }
        runCatching { art.detach() }
        coord.shutdownNow()
        publish(DebugState.Detached)
    }

    companion object {
        fun attach(
            device: DebugDevice, pkg: String, pid: Int, androidRelease: Int,
            nativeDebug: NativeDebug, registerMeta: (String) -> RegisterMeta?,
            nativeBreakpoints: List<Breakpoint.Native> = emptyList(),
            dexBreakpoints: List<Breakpoint.Dex> = emptyList(),
            bindingMap: BindingMap = BindingMap(),
            nativeSymbol: (String, Long) -> String? = { _, _ -> null },
            log: (String) -> Unit = {},
        ): MixedSession {
            val art = ArtSession.attach(device, androidRelease, pid, registerMeta)
            art.suspendVm()
            dexBreakpoints.forEach { runCatching { art.addBreakpoint(it) } }
            return wire(art, device, pkg, pid, nativeDebug, nativeBreakpoints, bindingMap, nativeSymbol, log)
        }

        fun upgrade(
            art: ArtSession, device: DebugDevice, pkg: String, pid: Int,
            nativeDebug: NativeDebug,
            nativeBreakpoints: List<Breakpoint.Native> = emptyList(),
            bindingMap: BindingMap = BindingMap(),
            nativeSymbol: (String, Long) -> String? = { _, _ -> null },
            log: (String) -> Unit = {},
        ): MixedSession {
            art.suspendVm()
            return wire(art, device, pkg, pid, nativeDebug, nativeBreakpoints, bindingMap, nativeSymbol, log)
        }

        private fun wire(
            art: ArtSession, device: DebugDevice, pkg: String, pid: Int,
            nativeDebug: NativeDebug, nativeBreakpoints: List<Breakpoint.Native>,
            bindingMap: BindingMap, nativeSymbol: (String, Long) -> String?, log: (String) -> Unit,
        ): MixedSession {
            val native = NativeSession.attach(device, pkg, pid, nativeDebug)
            native.nativeSymbol = nativeSymbol
            native.jniSig = { id, vaddr -> bindingMap.entryAt(id, vaddr) }
            val rBrk = runCatching { native.setupRendezvous(log) }.getOrElse { log("rendezvous setup error: $it"); null }
            val s = MixedSession(art, native, bindingMap)
            s.log = log
            s.rendezvousAddr = rBrk
            nativeBreakpoints.forEach { bp ->
                if (native.runtimeAddr(bp.nativeId, bp.fileOffset) != null) runCatching { native.addBreakpoint(bp) }
                else s.deferredNative.add(bp)
            }
            native.resume()
            art.onStateChange(s::onArtState)
            native.onStateChange(s::onNativeState)
            native.onRawBreakpoint = { addr, regs -> s.coord.submit { s.onRawHit(addr, regs) } }
            s.armNativeEntries()
            s.current = DebugState.Running
            art.resume()
            return s
        }
    }
}
