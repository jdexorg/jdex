package io.github.nitanmarcel.jdex.debug

import io.github.skylot.jdwp.JDWP
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val REF_OBJECT = 0
private const val REF_ARRAY = 1
private const val REF_INHERITED = 2
private const val REF_STATIC = 3

class ArtSession private constructor(
    override val device: DebugDevice,
    override val pid: Int,
    private val socket: Socket,
    private val out: OutputStream,
    private val input: InputStream,
    private val jdwp: JDWP,
    androidRelease: Int,
    private val registerMeta: (String) -> RegisterMeta?,
) : DebugSession {

    override var state: DebugState = DebugState.Running
        private set

    private val piePlus = androidRelease > 8
    private val listeners = ArrayList<(DebugState) -> Unit>()
    private val writeLock = Any()
    private val nextId = AtomicInteger(10)
    private val callbacks = ConcurrentHashMap<Int, CompletableFuture<JDWP.Packet>>()
    private val eventExecutor = Executors.newSingleThreadExecutor { Thread(it, "jdex-art-events-$pid").apply { isDaemon = true } }
    @Volatile private var alive = true

    private val breakpoints = LinkedHashSet<Breakpoint.Dex>()
    private val bpReqIds = ConcurrentHashMap<Breakpoint.Dex, Int>()
    private val pendingByClass = ConcurrentHashMap<String, MutableList<Breakpoint.Dex>>()
    private val transientReqs = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var classPrepareReq = -1
    @Volatile private var exceptionReq = -1

    private val classSigToId = ConcurrentHashMap<String, Long>()
    private val classIdToSig = ConcurrentHashMap<Long, String>()
    private val methodsByClass = ConcurrentHashMap<Long, List<MethodInfo>>()
    private val fieldsByType = ConcurrentHashMap<Long, List<FieldInfo>>()
    @Volatile private var classesFetched = false

    @Volatile private var stoppedThreadId = -1L
    private val framesCache = ConcurrentHashMap<Long, List<RawFrame>>()
    private val refs = ConcurrentHashMap<Long, RefTarget>()
    private val refCounter = AtomicLong(1)
    private val superclassCache = ConcurrentHashMap<Long, Long>()
    private val varTableCache = ConcurrentHashMap<Pair<Long, Long>, List<VarSlot>>()

    private class MethodInfo(val methodID: Long, val name: String, val signature: String)
    private class FieldInfo(val fieldID: Long, val name: String, val signature: String, val isStatic: Boolean)
    private class RawFrame(val frameID: Long, val classID: Long, val methodID: Long, val index: Long)
    private class RefTarget(val id: Long, val kind: Int)
    private class VarSlot(val slot: Int, val name: String, val signature: String, val start: Long, val end: Long)

    companion object {
        fun attach(device: DebugDevice, androidRelease: Int, pid: Int, registerMeta: (String) -> RegisterMeta?): ArtSession {
            val port = DeviceBridge.debuggerPort(device.serial, pid)
                ?: throw IllegalStateException("No JDWP debugger port for pid $pid (is the app debuggable?)")
            val socket = Socket("127.0.0.1", port)
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(JDWP.encodeHandShakePacket())
            out.flush()
            val hs = input.readNBytes(14)
            check(hs.size == 14 && JDWP.decodeHandShakePacket(hs)) { "JDWP handshake failed" }
            out.write(JDWP.IDSizes.encode().setPacketID(1).getBytes())
            out.flush()
            val sizesPkt = readPacket(input) ?: error("no IDSizes reply")
            val sizes = JDWP.IDSizes.decode(sizesPkt.getBuf(), JDWP.PACKET_HEADER_SIZE)
            val jdwp = JDWP(sizes)
            return ArtSession(device, pid, socket, out, input, jdwp, androidRelease, registerMeta).also { it.start() }
        }

        private fun readPacket(input: InputStream): JDWP.Packet? {
            val header = input.readNBytes(JDWP.PACKET_HEADER_SIZE)
            if (header.size < JDWP.PACKET_HEADER_SIZE) return null
            val bodyLen = JDWP.getPacketLength(header, 0) - JDWP.PACKET_HEADER_SIZE
            if (bodyLen <= 0) return JDWP.Packet.make(header)
            val body = input.readNBytes(bodyLen)
            return JDWP.Packet.make(header + body)
        }
    }

    private fun start() {
        Thread({ readLoop() }, "jdex-art-$pid").apply { isDaemon = true }.start()
    }

    override fun onStateChange(listener: (DebugState) -> Unit) { listeners.add(listener) }

    private fun setState(s: DebugState) {
        state = s
        listeners.toList().forEach { runCatching { it(s) } }
    }

    private fun sendSync(buf: JDWP.ByteBuffer): JDWP.Packet {
        val id = nextId.getAndIncrement()
        val fut = CompletableFuture<JDWP.Packet>()
        callbacks[id] = fut
        val bytes = buf.setPacketID(id).getBytes()
        synchronized(writeLock) { out.write(bytes); out.flush() }
        return try { fut.get(15, TimeUnit.SECONDS) } finally { callbacks.remove(id) }
    }

    private fun sendAsync(buf: JDWP.ByteBuffer) {
        val id = nextId.getAndIncrement()
        val bytes = buf.setPacketID(id).getBytes()
        synchronized(writeLock) { out.write(bytes); out.flush() }
    }

    private fun readLoop() {
        while (alive) {
            val pkt = runCatching { readPacket(input) }.getOrNull() ?: break
            val cb = callbacks.remove(pkt.getID())
            if (cb != null) { cb.complete(pkt); continue }
            if (runCatching { pkt.getCommandSetID() == 64 && pkt.getCommandID() == 100 }.getOrDefault(false)) {
                eventExecutor.execute { runCatching { handleEvents(pkt) } }
            }
        }
        if (alive) { alive = false; setState(DebugState.Detached) }
    }

    private fun handleEvents(pkt: JDWP.Packet) {
        val data = jdwp.event().cmdComposite().decode(pkt.getBuf(), JDWP.PACKET_HEADER_SIZE)
        for (ev in data.events) {
            when (ev) {
                is JDWP.Event.Composite.BreakpointEvent -> {
                    stopAt(ev.thread, JdwpAccess.bpClassID(ev), JdwpAccess.bpMethodID(ev), JdwpAccess.bpIndex(ev))
                    if (transientReqs.remove(ev.requestID)) {
                        runCatching { sendAsync(jdwp.eventRequest().cmdClear().encode(JDWP.EventKind.BREAKPOINT.toByte(), ev.requestID)) }
                    }
                }
                is JDWP.Event.Composite.SingleStepEvent -> stopAt(ev.thread, JdwpAccess.stepClassID(ev), JdwpAccess.stepMethodID(ev), JdwpAccess.stepIndex(ev))
                is JDWP.Event.Composite.ExceptionEvent -> stopAt(ev.thread, JdwpAccess.excClassID(ev), JdwpAccess.excMethodID(ev), JdwpAccess.excIndex(ev))
                is JDWP.Event.Composite.ClassPrepareEvent -> onClassPrepared(ev.signature, ev.typeID)
                is JDWP.Event.Composite.VMDeathEvent -> { alive = false; setState(DebugState.Detached) }
            }
        }
    }

    private fun stopAt(thread: Long, classID: Long, methodID: Long, index: Long) {
        clearTransient()
        stoppedThreadId = thread
        setState(DebugState.Stopped(DebugLocation.Dex(descriptorOf(classID, methodID), index.toInt())))
    }

    private fun clearTransient() {
        framesCache.clear()
        refs.clear()
    }

    override fun addBreakpoint(bp: Breakpoint) {
        if (bp !is Breakpoint.Dex) return
        breakpoints.add(bp)
        runCatching {
            val sig = classSigOf(bp.methodDescriptor)
            val classID = classID(sig)
            if (classID > 0) install(classID, bp) else deferPrepare(sig, bp)
        }
    }

    override fun removeBreakpoint(bp: Breakpoint) {
        if (bp !is Breakpoint.Dex) return
        breakpoints.remove(bp)
        pendingByClass[classSigOf(bp.methodDescriptor)]?.remove(bp)
        bpReqIds.remove(bp)?.let { reqID ->
            runCatching { sendAsync(jdwp.eventRequest().cmdClear().encode(JDWP.EventKind.BREAKPOINT.toByte(), reqID)) }
        }
    }

    private fun install(classID: Long, bp: Breakpoint.Dex) {
        val methodID = methodID(classID, methodNameSigOf(bp.methodDescriptor))
        if (methodID < 0) return
        val reqID = installLocation(classID, methodID, bp.dexPc)
        if (reqID >= 0) bpReqIds[bp] = reqID
    }

    private fun installLocation(classID: Long, methodID: Long, dexPc: Int): Int {
        val req = jdwp.eventRequest().cmdSet().newLocationOnlyRequest()
        JdwpAccess.setLocation(req, JDWP.TypeTag.CLASS, classID, methodID, dexPc.toLong())
        val pkt = sendSync(jdwp.eventRequest().cmdSet().encode(JDWP.EventKind.BREAKPOINT.toByte(), JDWP.SuspendPolicy.ALL.toByte(), listOf(req)))
        return if (!pkt.isError) jdwp.eventRequest().cmdSet().decodeRequestID(pkt.getBuf(), JDWP.PACKET_HEADER_SIZE) else -1
    }

    override fun setExceptionBreak(caught: Boolean, uncaught: Boolean) {
        runCatching {
            if (exceptionReq >= 0) {
                sendAsync(jdwp.eventRequest().cmdClear().encode(JDWP.EventKind.EXCEPTION.toByte(), exceptionReq))
                exceptionReq = -1
            }
            if (caught || uncaught) {
                val buf = jdwp.eventRequest().cmdSet().newExceptionOnlyRequest(
                    JDWP.EventKind.EXCEPTION.toByte(), JDWP.SuspendPolicy.ALL.toByte(), 0L, caught, uncaught,
                )
                val pkt = sendSync(buf)
                if (!pkt.isError) exceptionReq = jdwp.eventRequest().cmdSet().decodeRequestID(pkt.getBuf(), JDWP.PACKET_HEADER_SIZE)
            }
        }
    }

    override fun runToCursor(descriptor: String, dexPc: Int) {
        if (state !is DebugState.Stopped) return
        runCatching {
            val classID = classID(classSigOf(descriptor))
            if (classID <= 0) return
            val methodID = methodID(classID, methodNameSigOf(descriptor))
            if (methodID < 0) return
            val reqID = installLocation(classID, methodID, dexPc)
            if (reqID >= 0) transientReqs.add(reqID)
        }
        resume()
    }

    private fun deferPrepare(sig: String, bp: Breakpoint.Dex) {
        pendingByClass.getOrPut(sig) { ArrayList() }.add(bp)
        if (classPrepareReq < 0) {
            val match = jdwp.eventRequest().cmdSet().newClassMatchRequest().apply { classPattern = "*" }
            val pkt = sendSync(jdwp.eventRequest().cmdSet().encode(JDWP.EventKind.CLASS_PREPARE.toByte(), JDWP.SuspendPolicy.ALL.toByte(), listOf(match)))
            if (!pkt.isError) classPrepareReq = jdwp.eventRequest().cmdSet().decodeRequestID(pkt.getBuf(), JDWP.PACKET_HEADER_SIZE)
        }
    }

    private fun onClassPrepared(sig: String, typeID: Long) {
        classSigToId[sig] = typeID
        classIdToSig[typeID] = sig
        pendingByClass.remove(sig)?.forEach { runCatching { install(typeID, it) } }
        runCatching { resume() }
    }

    override fun resume() {
        clearTransient()
        stoppedThreadId = -1L
        sendAsync(JDWP.Resume.encode())
        setState(DebugState.Running)
    }

    override fun pause() {
        suspendVm()
        reportStop()
    }

    fun suspendVm() { runCatching { sendSync(JDWP.Suspend.encode()) } }

    fun reportStop() {
        clearTransient()
        val thread = allThreads().firstOrNull { framesOf(it).isNotEmpty() } ?: return
        stoppedThreadId = thread
        val top = framesOf(thread).firstOrNull() ?: return
        setState(DebugState.Stopped(DebugLocation.Dex(descriptorOf(top.classID, top.methodID), top.index.toInt())))
    }

    override fun stepInto() = step(JDWP.StepDepth.INTO)
    override fun stepOver() = step(JDWP.StepDepth.OVER)
    override fun stepOut() = step(JDWP.StepDepth.OUT)

    private fun step(depth: Int) {
        val thread = stoppedThreadId
        if (thread < 0) return
        val set = jdwp.eventRequest().cmdSet()
        val stepReq = set.newStepRequest().apply { this.thread = thread; this.size = JDWP.StepSize.MIN; this.depth = depth }
        val count = set.newCountRequest().apply { this.count = 1 }
        runCatching { sendSync(set.encode(JDWP.EventKind.SINGLE_STEP.toByte(), JDWP.SuspendPolicy.ALL.toByte(), listOf(stepReq, count))) }
        resume()
    }

    override fun detach() {
        alive = false
        breakpoints.clear()
        runCatching { sendSync(jdwp.virtualMachine().cmdDispose().encode()) }
        runCatching { socket.close() }
        runCatching { eventExecutor.shutdownNow() }
        setState(DebugState.Detached)
    }

    override fun currentThreadId(): Long = stoppedThreadId

    override fun threads(): List<ThreadInfo> {
        if (state !is DebugState.Stopped) return emptyList()
        return runCatching {
            allThreads().map { id ->
                ThreadInfo(id, threadName(id), threadState(id), id == stoppedThreadId)
            }.sortedByDescending { it.current }
        }.getOrDefault(emptyList())
    }

    override fun frames(threadId: Long): List<Frame> {
        if (state !is DebugState.Stopped) return emptyList()
        return framesOf(threadId).mapIndexed { i, f ->
            Frame(i, descriptionOf(f.classID, f.methodID), DebugLocation.Dex(descriptorOf(f.classID, f.methodID), f.index.toInt()))
        }
    }

    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> {
        if (state !is DebugState.Stopped) return emptyList()
        val frame = framesOf(threadId).getOrNull(frameIndex) ?: return emptyList()
        val descriptor = descriptorOf(frame.classID, frame.methodID)
        val meta = runCatching { registerMeta(descriptor) }.getOrNull()
        val out = ArrayList<DebugVar>()
        if (meta == null || !meta.isStatic) {
            runCatching { thisObject(threadId, frame.frameID) }.getOrNull()?.takeIf { it != 0L }?.let {
                val v = objectV(it, null)
                out += DebugVar("this", v.type, v.value, ref = v.ref, id = v.id)
            }
        }
        if (meta != null) {
            for (s in 0 until meta.registerCount) {
                val slot = if (piePlus) s else (s + (meta.registerCount - meta.paramStart)) % meta.registerCount
                val local = liveLocal(frame.classID, frame.methodID, frame.index, slot)
                val name = registerName(s, meta, local?.name)
                out += if (local != null) {
                    readRegister(threadId, frame.frameID, slot, name, sigToTag(local.signature), sigToType(local.signature))
                } else {
                    readRegister(threadId, frame.frameID, slot, name, null, null)
                }
            }
        }
        return out
    }

    override fun children(ref: Long): List<DebugVar> {
        if (state !is DebugState.Stopped) return emptyList()
        val target = refs[ref] ?: return emptyList()
        return runCatching {
            if (target.kind == REF_ARRAY) arrayChildren(target.id) else objectChildren(target.id, target.kind)
        }.getOrDefault(emptyList())
    }

    private fun objectChildren(objId: Long, kind: Int): List<DebugVar> {
        val objClass = referenceType(objId)
        val all = allFields(objClass)
        return when (kind) {
            REF_INHERITED -> readInstanceFields(objId, all.filter { it.declType != objClass && !it.field.isStatic }.map { it.field })
            REF_STATIC -> readStaticFields(objClass, all.filter { it.field.isStatic }.map { it.field })
            else -> {
                val own = all.filter { it.declType == objClass && !it.field.isStatic }.map { it.field }
                val out = ArrayList(readInstanceFields(objId, own))
                val inherited = all.count { it.declType != objClass && !it.field.isStatic }
                val statics = all.count { it.field.isStatic }
                if (inherited > 0) out += DebugVar("(inherited)", "", "$inherited fields", ref = registerRef(objId, REF_INHERITED))
                if (statics > 0) out += DebugVar("(static)", "", "$statics fields", ref = registerRef(objId, REF_STATIC))
                out
            }
        }
    }

    private fun readInstanceFields(objId: Long, fields: List<FieldInfo>): List<DebugVar> {
        if (fields.isEmpty()) return emptyList()
        val cmd = jdwp.objectReference().cmdGetValues()
        val values = runCatching { cmd.decode(sendSync(cmd.encode(objId, fields.map { it.fieldID })).getBuf(), JDWP.PACKET_HEADER_SIZE).values }.getOrNull()
            ?: return fields.map { DebugVar(it.name, sigToType(it.signature), "<unavailable>", available = false) }
        return fields.mapIndexed { i, f ->
            val vp = values[i].value
            val v = decodeValue(vp, sigToType(f.signature))
            val key = if (editable(vp.tag)) "if:$objId:${f.fieldID}:${vp.tag}" else null
            DebugVar(f.name, v.type, v.value, ref = v.ref, editKey = key, editValue = v.edit, id = v.id)
        }.sortedBy { it.name }
    }

    private fun readStaticFields(typeID: Long, fields: List<FieldInfo>): List<DebugVar> {
        if (fields.isEmpty()) return emptyList()
        val cmd = jdwp.referenceType().cmdGetValues()
        val values = runCatching { cmd.decode(sendSync(cmd.encode(typeID, fields.map { it.fieldID })).getBuf(), JDWP.PACKET_HEADER_SIZE).values }.getOrNull()
            ?: return fields.map { DebugVar(it.name, sigToType(it.signature), "<unavailable>", available = false) }
        return fields.mapIndexed { i, f ->
            val vp = values[i].value
            val v = decodeValue(vp, sigToType(f.signature))
            val key = if (editable(vp.tag)) "sf:$typeID:${f.fieldID}:${vp.tag}" else null
            DebugVar(f.name, v.type, v.value, ref = v.ref, id = v.id, editKey = key, editValue = v.edit)
        }.sortedBy { it.name }
    }

    private fun arrayChildren(arrayId: Long): List<DebugVar> {
        val lenCmd = jdwp.arrayReference().cmdLength()
        val len = runCatching { lenCmd.decode(sendSync(lenCmd.encode(arrayId)).getBuf(), JDWP.PACKET_HEADER_SIZE).arrayLength }.getOrDefault(0)
        if (len <= 0) return emptyList()
        val n = minOf(len, 200)
        val gv = jdwp.arrayReference().cmdGetValues()
        val reply = runCatching { gv.decode(sendSync(gv.encode(arrayId, 0, n)).getBuf(), JDWP.PACKET_HEADER_SIZE) }.getOrNull() ?: return emptyList()
        val tag = JdwpAccess.arrayTag(reply)
        val raws = JdwpAccess.arrayValues(reply)
        val elemType = runCatching { sigToType(signatureOf(referenceType(arrayId)).removePrefix("[")) }.getOrNull()
        val editableTag = editable(tag)
        val out = raws.mapIndexed { i, raw ->
            val v = decodeRaw(tag, raw, elemType)
            val key = if (editableTag) "ae:$arrayId:$i:$tag" else null
            DebugVar("[$i]", v.type, v.value, ref = v.ref, id = v.id, editKey = key, editValue = v.edit)
        }.toMutableList()
        if (len > n) out += DebugVar("…", "", "${len - n} more", available = false)
        return out
    }

    private fun allFields(typeID: Long): List<TypedField> {
        val out = ArrayList<TypedField>()
        var t = typeID
        val seen = HashSet<Long>()
        while (t != 0L && seen.add(t)) {
            fieldsOf(t).forEach { out += TypedField(t, it) }
            t = superclassOf(t)
        }
        return out
    }

    private fun superclassOf(typeID: Long): Long = superclassCache.getOrPut(typeID) {
        runCatching {
            val cmd = jdwp.classType().cmdSuperclass()
            cmd.decode(sendSync(cmd.encode(typeID)).getBuf(), JDWP.PACKET_HEADER_SIZE).superclass
        }.getOrDefault(0L)
    }

    private class TypedField(val declType: Long, val field: FieldInfo)

    override fun readMemory(address: Long, length: Int): ByteArray? = null

    override fun setValue(editKey: String, text: String): Boolean {
        if (state !is DebugState.Stopped) return false
        val p = editKey.split(":")
        return runCatching {
            when (p[0]) {
                "r" -> {
                    val (tag, value) = coerce(p[3].toInt(), text) ?: return false
                    !sendSync(JdwpAccess.encodeSetRegister(jdwp, stoppedThreadId, p[1].toLong(), p[2].toInt(), tag, value)).isError
                }
                "if" -> {
                    val (_, value) = coerce(p[3].toInt(), text) ?: return false
                    !sendSync(JdwpAccess.encodeSetField(jdwp, p[1].toLong(), p[2].toLong(), value)).isError
                }
                "sf" -> {
                    val (_, value) = coerce(p[3].toInt(), text) ?: return false
                    !sendSync(JdwpAccess.encodeSetStatic(jdwp, p[1].toLong(), p[2].toLong(), value)).isError
                }
                "ae" -> {
                    val (_, value) = coerce(p[3].toInt(), text) ?: return false
                    !sendSync(JdwpAccess.encodeSetArrayElement(jdwp, p[1].toLong(), p[2].toInt(), value)).isError
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun editable(tag: Int): Boolean = tag in intArrayOf(
        JDWP.Tag.BOOLEAN, JDWP.Tag.BYTE, JDWP.Tag.CHAR, JDWP.Tag.SHORT,
        JDWP.Tag.INT, JDWP.Tag.LONG, JDWP.Tag.FLOAT, JDWP.Tag.DOUBLE, JDWP.Tag.STRING,
    )

    private fun coerce(tag: Int, text: String): Pair<Int, Any>? {
        val t = text.trim()
        return runCatching {
            when (tag) {
                JDWP.Tag.BOOLEAN -> JDWP.Tag.BOOLEAN to (t.equals("true", true) || t == "1")
                JDWP.Tag.BYTE -> JDWP.Tag.BYTE to parseLong(t).toByte()
                JDWP.Tag.SHORT -> JDWP.Tag.SHORT to parseLong(t).toShort()
                JDWP.Tag.CHAR -> JDWP.Tag.CHAR to (t.firstOrNull() ?: return null)
                JDWP.Tag.INT -> JDWP.Tag.INT to parseLong(t).toInt()
                JDWP.Tag.LONG -> JDWP.Tag.LONG to parseLong(t)
                JDWP.Tag.FLOAT -> JDWP.Tag.FLOAT to t.toFloat()
                JDWP.Tag.DOUBLE -> JDWP.Tag.DOUBLE to t.toDouble()
                JDWP.Tag.STRING -> JDWP.Tag.OBJECT to createString(unquote(t))
                else -> null
            }
        }.getOrNull()
    }

    private fun unquote(t: String): String =
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') t.substring(1, t.length - 1) else t

    private fun parseLong(t: String): Long =
        if (t.startsWith("0x") || t.startsWith("0X")) java.lang.Long.parseUnsignedLong(t.substring(2), 16) else t.toLong()

    private fun createString(s: String): Long {
        val cmd = jdwp.virtualMachine().cmdCreateString()
        return cmd.decode(sendSync(cmd.encode(s)).getBuf(), JDWP.PACKET_HEADER_SIZE).stringObject
    }

    private fun readRegister(threadId: Long, frameId: Long, slot: Int, name: String, preferredTag: Int?, declaredType: String?): DebugVar {
        val tags = if (preferredTag != null) {
            intArrayOf(preferredTag, JDWP.Tag.OBJECT, JDWP.Tag.INT, JDWP.Tag.LONG).distinct().toIntArray()
        } else {
            intArrayOf(JDWP.Tag.OBJECT, JDWP.Tag.INT, JDWP.Tag.LONG)
        }
        for (tag in tags) {
            val vp = getRegister(threadId, frameId, slot, tag) ?: continue
            val v = decodeValue(vp, declaredType)
            val key = if (editable(vp.tag)) "r:$frameId:$slot:${vp.tag}" else null
            return DebugVar(name, v.type, v.value, ref = v.ref, editKey = key, editValue = v.edit, id = v.id)
        }
        return DebugVar(name, declaredType ?: "void", if (declaredType != null) "<unavailable>" else "void", available = false)
    }

    private fun getRegister(threadId: Long, frameId: Long, slot: Int, tag: Int): JDWP.ValuePacket? {
        val cmd = jdwp.stackFrame().cmdGetValues()
        val s = cmd.newValuesSlots().apply { this.slot = slot; this.sigbyte = tag.toByte() }
        val pkt = runCatching { sendSync(cmd.encode(threadId, frameId, listOf(s))) }.getOrNull() ?: return null
        if (pkt.isError) return null
        return runCatching { cmd.decode(pkt.getBuf(), JDWP.PACKET_HEADER_SIZE).values[0].slotValue }.getOrNull()
    }

    private class V(val type: String, val value: String, val ref: Long, val edit: String? = null, val id: Long = 0L)

    private fun decodeValue(vp: JDWP.ValuePacket, declaredType: String?): V = runCatching {
        when (vp.tag) {
            JDWP.Tag.BOOLEAN -> vp.getBoolean().toString().let { V("boolean", it, 0, it) }
            JDWP.Tag.BYTE -> vp.getByte().let { fmtInt(it.toLong(), (it.toInt() and 0xff).toString(16)).let { s -> V("byte", s, 0, s) } }
            JDWP.Tag.CHAR -> vp.getChar().let { V("char", "'$it'", 0, it.toString()) }
            JDWP.Tag.SHORT -> vp.getShort().let { fmtInt(it.toLong(), (it.toInt() and 0xffff).toString(16)).let { s -> V("short", s, 0, s) } }
            JDWP.Tag.INT -> { val i = vp.getInt(); fmtInt(i.toLong(), i.toUInt().toString(16)).let { V("int", it, 0, it) } }
            JDWP.Tag.LONG -> { val l = vp.getLong(); fmtInt(l, java.lang.Long.toHexString(l)).let { V("long", it, 0, it) } }
            JDWP.Tag.FLOAT -> vp.getFloat().toString().let { V("float", it, 0, it) }
            JDWP.Tag.DOUBLE -> vp.getDouble().toString().let { V("double", it, 0, it) }
            JDWP.Tag.VOID -> V("void", "void", 0)
            JDWP.Tag.STRING -> {
                val id = vp.getID()
                if (id == 0L) V("java.lang.String", "null", 0)
                else readString(id).let { V("java.lang.String", "\"${truncate(it)}\"", 0, edit = it) }
            }
            JDWP.Tag.ARRAY -> arrayV(vp.getID(), declaredType)
            JDWP.Tag.OBJECT, JDWP.Tag.THREAD, JDWP.Tag.THREAD_GROUP, JDWP.Tag.CLASS_LOADER, JDWP.Tag.CLASS_OBJECT -> objectV(vp.getID(), declaredType)
            else -> V("?", "?", 0)
        }
    }.getOrDefault(V(declaredType ?: "?", "<unavailable>", 0))

    private fun objectV(id: Long, declaredType: String?): V {
        if (id == 0L) return V(declaredType ?: "object", "null", 0)
        val type = declaredType ?: objectType(id)
        val preview = unbox(id, type) ?: objectPreview(id, type) ?: simpleName(type)
        return V(type, preview, registerRef(id, REF_OBJECT), id = id)
    }

    private fun arrayV(id: Long, declaredType: String?): V {
        if (id == 0L) return V(declaredType ?: "array", "null", 0)
        return V(declaredType ?: "array", arrayPreview(id, declaredType), registerRef(id, REF_ARRAY), id = id)
    }

    private fun decodeRaw(tag: Int, raw: Long, declaredType: String?): V = runCatching {
        when (tag) {
            JDWP.Tag.BOOLEAN -> (raw != 0L).toString().let { V("boolean", it, 0, it) }
            JDWP.Tag.BYTE -> raw.toByte().let { fmtInt(it.toLong(), (it.toInt() and 0xff).toString(16)).let { s -> V("byte", s, 0, s) } }
            JDWP.Tag.CHAR -> raw.toInt().toChar().let { V("char", "'$it'", 0, it.toString()) }
            JDWP.Tag.SHORT -> raw.toShort().let { fmtInt(it.toLong(), (it.toInt() and 0xffff).toString(16)).let { s -> V("short", s, 0, s) } }
            JDWP.Tag.INT -> raw.toInt().let { fmtInt(it.toLong(), it.toUInt().toString(16)).let { s -> V("int", s, 0, s) } }
            JDWP.Tag.LONG -> fmtInt(raw, java.lang.Long.toHexString(raw)).let { V("long", it, 0, it) }
            JDWP.Tag.FLOAT -> Float.fromBits(raw.toInt()).toString().let { V("float", it, 0, it) }
            JDWP.Tag.DOUBLE -> Double.fromBits(raw).toString().let { V("double", it, 0, it) }
            JDWP.Tag.STRING -> V("java.lang.String", if (raw == 0L) "null" else "\"${truncate(readString(raw))}\"", 0)
            JDWP.Tag.ARRAY -> arrayV(raw, declaredType)
            else -> objectV(raw, declaredType)
        }
    }.getOrDefault(V(declaredType ?: "?", "<unavailable>", 0))

    private fun fmtInt(v: Long, unsignedHex: String): String = if (DebugFormat.hex) "0x$unsignedHex" else "$v"

    private fun arrayPreview(id: Long, declaredType: String?): String {
        val len = runCatching {
            val c = jdwp.arrayReference().cmdLength(); c.decode(sendSync(c.encode(id)).getBuf(), JDWP.PACKET_HEADER_SIZE).arrayLength
        }.getOrNull()
        val base = declaredType ?: runCatching { sigToType(signatureOf(referenceType(id))) }.getOrDefault("array")
        return if (len != null && base.endsWith("[]")) base.dropLast(1) + len + "]" else base
    }

    private fun unbox(id: Long, type: String): String? {
        if (type !in boxedTypes) return null
        return runCatching {
            val f = fieldsOf(referenceType(id)).firstOrNull { it.name == "value" } ?: return null
            val cmd = jdwp.objectReference().cmdGetValues()
            val vp = cmd.decode(sendSync(cmd.encode(id, listOf(f.fieldID))).getBuf(), JDWP.PACKET_HEADER_SIZE).values[0].value
            decodeValue(vp, null).value
        }.getOrNull()
    }

    private val boxedTypes = setOf(
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double",
    )

    private val collectionTypes = setOf(
        "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector", "java.util.HashSet",
        "java.util.LinkedHashSet", "java.util.TreeSet", "java.util.HashMap", "java.util.LinkedHashMap",
        "java.util.TreeMap", "java.util.concurrent.ConcurrentHashMap",
    )

    private fun objectPreview(id: Long, type: String): String? = runCatching {
        when {
            type == "java.lang.StringBuilder" || type == "java.lang.StringBuffer" -> stringBuilderPreview(id)
            type in collectionTypes -> readField(id, referenceType(id), "size")?.let { "size=${it.getInt()}" }
            isSubclass(referenceType(id), "Ljava/lang/Throwable;") -> throwablePreview(id)
            else -> null
        }
    }.getOrNull()

    private fun stringBuilderPreview(id: Long): String? {
        val typeID = referenceType(id)
        val count = readField(id, typeID, "count")?.getInt() ?: return null
        val arrId = readField(id, typeID, "value")?.getID() ?: return null
        if (arrId == 0L || count <= 0) return "\"\""
        val n = minOf(count, PREVIEW_CAP)
        val gv = jdwp.arrayReference().cmdGetValues()
        val reply = gv.decode(sendSync(gv.encode(arrId, 0, n)).getBuf(), JDWP.PACKET_HEADER_SIZE)
        val s = buildString { JdwpAccess.arrayValues(reply).forEach { append(it.toInt().toChar()) } }
        return "\"$s${if (count > n) "…" else ""}\""
    }

    private fun throwablePreview(id: Long): String? {
        val msgId = readField(id, referenceType(id), "detailMessage")?.getID() ?: return null
        return if (msgId == 0L) null else "\"${truncate(readString(msgId))}\""
    }

    private fun readField(objId: Long, typeID: Long, name: String): JDWP.ValuePacket? {
        val f = allFields(typeID).firstOrNull { it.field.name == name }?.field ?: return null
        val cmd = jdwp.objectReference().cmdGetValues()
        return cmd.decode(sendSync(cmd.encode(objId, listOf(f.fieldID))).getBuf(), JDWP.PACKET_HEADER_SIZE).values[0].value
    }

    private fun isSubclass(typeID: Long, targetSig: String): Boolean {
        var t = typeID
        val seen = HashSet<Long>()
        while (t != 0L && seen.add(t)) {
            if (signatureOf(t) == targetSig) return true
            t = superclassOf(t)
        }
        return false
    }

    private fun simpleName(type: String): String = type.substringAfterLast('.')

    private fun truncate(s: String): String = if (s.length > PREVIEW_CAP) s.take(PREVIEW_CAP) + "…" else s

    private val PREVIEW_CAP = 256

    private fun registerName(smaliNum: Int, meta: RegisterMeta, localName: String?): String {
        val parts = ArrayList<String>(2)
        if (smaliNum >= meta.paramStart) parts += "p${smaliNum - meta.paramStart}"
        if (localName != null) parts += localName
        return if (parts.isEmpty()) "v$smaliNum" else "v$smaliNum (${parts.joinToString(", ")})"
    }

    private fun liveLocal(classID: Long, methodID: Long, dexPc: Long, slot: Int): VarSlot? =
        varTable(classID, methodID).firstOrNull { it.slot == slot && dexPc >= it.start && dexPc < it.end }

    private fun varTable(classID: Long, methodID: Long): List<VarSlot> = varTableCache.getOrPut(classID to methodID) {
        runCatching {
            val cmd = jdwp.method().cmdVariableTableWithGeneric()
            cmd.decode(sendSync(cmd.encode(classID, methodID)).getBuf(), JDWP.PACKET_HEADER_SIZE).slots
                .map { VarSlot(it.slot, it.name, it.signature, it.codeIndex, it.codeIndex + it.length) }
        }.getOrDefault(emptyList())
    }

    private fun sigToTag(sig: String): Int = when (sig.firstOrNull()) {
        'L' -> JDWP.Tag.OBJECT
        '[' -> JDWP.Tag.ARRAY
        'I' -> JDWP.Tag.INT
        'J' -> JDWP.Tag.LONG
        'Z' -> JDWP.Tag.BOOLEAN
        'B' -> JDWP.Tag.BYTE
        'C' -> JDWP.Tag.CHAR
        'S' -> JDWP.Tag.SHORT
        'F' -> JDWP.Tag.FLOAT
        'D' -> JDWP.Tag.DOUBLE
        else -> JDWP.Tag.OBJECT
    }

    private fun registerRef(objId: Long, kind: Int): Long = refCounter.getAndIncrement().also { refs[it] = RefTarget(objId, kind) }

    private fun allThreads(): List<Long> = runCatching {
        val cmd = jdwp.virtualMachine().cmdAllThreads()
        cmd.decode(sendSync(cmd.encode()).getBuf(), JDWP.PACKET_HEADER_SIZE).threads.map { it.thread }
    }.getOrDefault(emptyList())

    private fun threadName(id: Long): String = runCatching {
        val cmd = jdwp.threadReference().cmdName()
        cmd.decode(sendSync(cmd.encode(id)).getBuf(), JDWP.PACKET_HEADER_SIZE).threadName
    }.getOrDefault("?")

    private fun threadState(id: Long): String = runCatching {
        val cmd = jdwp.threadReference().cmdStatus()
        when (cmd.decode(sendSync(cmd.encode(id)).getBuf(), JDWP.PACKET_HEADER_SIZE).threadStatus) {
            JDWP.ThreadStatus.RUNNING -> "running"
            JDWP.ThreadStatus.SLEEPING -> "sleeping"
            JDWP.ThreadStatus.MONITOR -> "monitor"
            JDWP.ThreadStatus.WAIT -> "waiting"
            JDWP.ThreadStatus.ZOMBIE -> "zombie"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    private fun framesOf(threadId: Long): List<RawFrame> = framesCache.getOrPut(threadId) {
        runCatching {
            val cmd = jdwp.threadReference().cmdFrames()
            cmd.decode(sendSync(cmd.encode(threadId, 0, -1)).getBuf(), JDWP.PACKET_HEADER_SIZE).frames
                .map { RawFrame(it.frameID, JdwpAccess.frameClassID(it), JdwpAccess.frameMethodID(it), JdwpAccess.frameIndex(it)) }
        }.getOrDefault(emptyList())
    }

    private fun thisObject(threadId: Long, frameId: Long): Long {
        val cmd = jdwp.stackFrame().cmdThisObject()
        return JdwpAccess.thisObjectID(cmd.decode(sendSync(cmd.encode(threadId, frameId)).getBuf(), JDWP.PACKET_HEADER_SIZE))
    }

    private fun readString(id: Long): String = runCatching {
        val cmd = jdwp.stringReference().cmdValue()
        cmd.decode(sendSync(cmd.encode(id)).getBuf(), JDWP.PACKET_HEADER_SIZE).stringValue
    }.getOrDefault("?")

    private fun referenceType(objId: Long): Long {
        val cmd = jdwp.objectReference().cmdReferenceType()
        return cmd.decode(sendSync(cmd.encode(objId)).getBuf(), JDWP.PACKET_HEADER_SIZE).typeID
    }

    private fun objectType(objId: Long): String = runCatching { sigToType(signatureOf(referenceType(objId))) }.getOrDefault("object")

    private fun signatureOf(typeID: Long): String = classIdToSig.getOrPut(typeID) {
        runCatching {
            val cmd = jdwp.referenceType().cmdSignature()
            cmd.decode(sendSync(cmd.encode(typeID)).getBuf(), JDWP.PACKET_HEADER_SIZE).signature
        }.getOrDefault("Ljava/lang/Object;")
    }

    private fun classID(sig: String): Long {
        classSigToId[sig]?.let { return it }
        repeat(3) {
            fetchAllClasses()
            classSigToId[sig]?.let { return it }
            Thread.sleep(120)
        }
        return -1L
    }

    private fun fetchAllClasses() {
        runCatching {
            val cmd = jdwp.virtualMachine().cmdAllClassesWithGeneric()
            val classes = cmd.decode(sendSync(cmd.encode()).getBuf(), JDWP.PACKET_HEADER_SIZE).classes
            classes.forEach {
                classSigToId[it.signature] = it.typeID
                classIdToSig[it.typeID] = it.signature
            }
            if (classes.isNotEmpty()) classesFetched = true
        }
    }

    private fun methodsOf(classID: Long): List<MethodInfo> = methodsByClass.getOrPut(classID) {
        runCatching {
            val cmd = jdwp.referenceType().cmdMethodsWithGeneric()
            cmd.decode(sendSync(cmd.encode(classID)).getBuf(), JDWP.PACKET_HEADER_SIZE).declared
                .map { MethodInfo(it.methodID, it.name, it.signature) }
        }.getOrDefault(emptyList())
    }

    private fun fieldsOf(typeID: Long): List<FieldInfo> = fieldsByType.getOrPut(typeID) {
        runCatching {
            val cmd = jdwp.referenceType().cmdFieldsWithGeneric()
            cmd.decode(sendSync(cmd.encode(typeID)).getBuf(), JDWP.PACKET_HEADER_SIZE).declared
                .map { FieldInfo(it.fieldID, it.name, it.signature, (it.modBits and 0x8) != 0) }
        }.getOrDefault(emptyList())
    }

    private fun methodID(classID: Long, nameSig: String): Long =
        methodsOf(classID).firstOrNull { nameSig.startsWith(it.name + "(") && nameSig.endsWith(it.signature) }?.methodID ?: -1L

    private fun descriptorOf(classID: Long, methodID: Long): String {
        val sig = classIdToSig[classID] ?: signatureOf(classID)
        val m = methodsOf(classID).firstOrNull { it.methodID == methodID }
        return "$sig->" + (if (m != null) m.name + m.signature else "?")
    }

    private fun descriptionOf(classID: Long, methodID: Long): String {
        val sig = classIdToSig[classID] ?: signatureOf(classID)
        val dotted = sig.removePrefix("L").removeSuffix(";").replace('/', '.')
        val m = methodsOf(classID).firstOrNull { it.methodID == methodID }
        return "$dotted.${m?.name ?: "?"}"
    }

    private fun classSigOf(descriptor: String): String = descriptor.substringBefore("->")

    private fun methodNameSigOf(descriptor: String): String = descriptor.substringAfter("->")

    private fun sigToType(sig: String): String {
        var dims = 0
        var s = sig
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        val base = when (s.firstOrNull()) {
            'L' -> s.removePrefix("L").removeSuffix(";").replace('/', '.')
            'I' -> "int"; 'J' -> "long"; 'Z' -> "boolean"; 'B' -> "byte"
            'C' -> "char"; 'S' -> "short"; 'F' -> "float"; 'D' -> "double"; 'V' -> "void"
            else -> s
        }
        return base + "[]".repeat(dims)
    }
}
