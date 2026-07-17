package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.disasm.CapstoneDisassembler
import io.github.nitanmarcel.jdex.disasm.ElfArch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeSession private constructor(
    override val device: DebugDevice,
    override val pid: Int,
    private val pkg: String,
    private val rsp: RspClient,
    private val server: AutoCloseable,
    private val localPort: Int,
    private val regInfos: List<RegInfo>,
    resolverInit: ModuleResolver,
    private val arch: ElfArch,
) : DebugSession {

    private val is64 = arch == ElfArch.ARM64 || arch == ElfArch.X86_64 || arch == ElfArch.MIPS64
    private val ptrSize = if (is64) 8 else 4
    private val bpKind = if (arch == ElfArch.X86 || arch == ElfArch.X86_64) 1 else 4

    private fun roleReg(generic: String, fallback: String): String =
        regInfos.firstOrNull { it.generic == generic }?.name ?: fallback
    private val pcReg = roleReg("pc", when (arch) { ElfArch.X86_64 -> "rip"; ElfArch.X86 -> "eip"; else -> "pc" })
    private val spReg = roleReg("sp", when (arch) { ElfArch.X86_64 -> "rsp"; ElfArch.X86 -> "esp"; else -> "sp" })
    private val fpReg = roleReg("fp", when (arch) { ElfArch.ARM64 -> "x29"; ElfArch.ARM -> "r11"; ElfArch.X86_64 -> "rbp"; ElfArch.X86 -> "ebp"; else -> "fp" })
    private val flagsReg = roleReg("flags", if (arch == ElfArch.X86 || arch == ElfArch.X86_64) "eflags" else "cpsr")

    @Volatile private var resolver = resolverInit

    @Volatile private var current: DebugState = DebugState.Running
    @Volatile private var stateListener: (DebugState) -> Unit = {}
    private val breakpoints = HashMap<Breakpoint.Native, Long>()
    private val events = Executors.newSingleThreadExecutor { Thread(it, "jdex-native-events").apply { isDaemon = true } }
    private val stopSignals = setOf(2, 4, 5, 6, 7, 8, 19, 31)
    private val rawBreakpoints = HashSet<Long>()
    @Volatile private var runToAddr: Long? = null
    @Volatile private var stepRearm: List<Long> = emptyList()
    @Volatile private var rawStopped = false
    @Volatile private var armingLatch: CountDownLatch? = null
    var onRawBreakpoint: ((Long, Map<String, ULong>) -> Unit)? = null
    var nativeSymbol: (String, Long) -> String? = { _, _ -> null }
    var jniSig: (String, Long) -> Pair<String, Boolean>? = { _, _ -> null }

    override val state: DebugState get() = current

    override fun onStateChange(listener: (DebugState) -> Unit) { stateListener = listener }

    private fun publish(s: DebugState) { current = s; stateListener(s) }

    private fun threadHex(): String = currentThreadId().toString(16)

    private fun readAllRegs(): Map<String, ULong> = decodeG(regInfos, rsp.command("g")).toMap()

    private fun readRegister(name: String): Long = readAllRegs()[name]?.toLong() ?: 0L

    private fun writeRegister(name: String, value: Long): Boolean {
        val info = regInfos.firstOrNull { it.name == name } ?: return false
        val g = rsp.command("g")
        if (g.isEmpty() || g[0] == 'E') return false
        val sb = StringBuilder(g)
        for (b in 0 until info.bitsize / 8) {
            val pos = (info.offset + b) * 2
            if (pos + 2 <= sb.length) sb.replace(pos, pos + 2, "%02x".format(((value.toULong() shr (b * 8)) and 0xffuL).toInt()))
        }
        return rsp.command("G$sb") == "OK"
    }

    private fun locationOf(pc: Long): DebugLocation {
        val (id, vaddr) = resolver.resolve(pc) ?: return DebugLocation.Native(null, 0, pc)
        return DebugLocation.Native(id, vaddr, pc)
    }

    private fun pcToLocation(): DebugLocation = locationOf(readRegister(pcReg))

    private fun frameDesc(pc: Long): String {
        val (id, vaddr) = resolver.resolve(pc) ?: return "0x${pc.toString(16)}"
        return nativeSymbol(id, vaddr)?.let { "$it  ($id)" } ?: "$id!0x${vaddr.toString(16)}"
    }

    private fun leN(b: ByteArray, o: Int, n: Int): Long {
        var r = 0L
        for (k in 0 until n) r = r or ((b[o + k].toLong() and 0xff) shl (8 * k))
        return r
    }

    private fun onStop(reply: String, fromStep: Boolean = false) {
        val kind = reply.firstOrNull()
        if (kind == 'W' || kind == 'X') { closeResources(); publish(DebugState.Detached); return }
        armingLatch?.let { armingLatch = null; it.countDown(); return }
        val sig = reply.drop(1).take(2).toIntOrNull(16) ?: 0
        val shouldStop = reply.contains("reason:breakpoint") || reply.contains("reason:trace") ||
            reply.contains("reason:watchpoint") || sig in stopSignals
        if (!shouldStop) { rsp.post("C" + "%02x".format(sig)); return }
        events.submit {
            runToAddr?.let { a -> runToAddr = null; if (a !in breakpoints.values && a !in rawBreakpoints) runCatching { clearBp(a) } }
            if (stepRearm.isNotEmpty()) { stepRearm.forEach { runCatching { setBp(it) } }; stepRearm = emptyList() }
            val regs = readAllRegs()
            var pc = regs[pcReg]?.toLong() ?: 0L
            if (!fromStep && (arch == ElfArch.X86 || arch == ElfArch.X86_64) && (pc - 1) in swBreakpoints && pc !in swBreakpoints) {
                pc -= 1
                writeRegister(pcReg, pc)
            }
            if (pc in rawBreakpoints) { rawStopped = true; onRawBreakpoint?.invoke(pc, regs) }
            else publish(DebugState.Stopped(locationOf(pc)))
        }
    }

    override fun runtimeAddr(nativeId: String, vaddr: Long): Long? = resolver.runtimeAddr(nativeId, vaddr)

    fun refreshModules() {
        resolver = ModuleResolver(parseMaps(DeviceBridge.readProcMaps(device.serial, pkg, pid)))
    }

    @Volatile private var rDebugAddr: Long = 0L

    fun setupRendezvous(log: (String) -> Unit = {}): Long? {
        val auxv = DeviceBridge.readAuxv(device.serial, pkg, pid)
        if (auxv == null) { log("rendezvous: auxv read failed"); return null }
        val rdv = Rendezvous.resolve(auxv, is64, ::readMemory)
        if (rdv == null) { log("rendezvous: resolve failed from ${auxv.size}-byte auxv"); return null }
        rDebugAddr = rdv.rDebug
        armRaw(rdv.rBrk)
        log("rendezvous armed: r_brk=0x${rdv.rBrk.toString(16)}")
        return rdv.rBrk
    }

    fun rendezvousState(): Int? {
        if (rDebugAddr == 0L) return null
        val b = readMemory(rDebugAddr + Rendezvous.stateOffset(is64), 4) ?: return null
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
    }

    private val linkBases = HashMap<String, Long>()
    @Volatile private var linkTail = 0L

    fun libBase(basename: String): Long? = linkBases[basename]

    fun refreshLinkMap() {
        if (rDebugAddr == 0L) return
        val rMapOff = if (is64) 8 else 4
        var node = if (linkTail != 0L) readPtr(linkTail + 3 * ptrSize) else readPtr(rDebugAddr + rMapOff)
        var guard = 0
        while (node != 0L && guard++ < 8000) {
            val lAddr = readPtr(node)
            val lName = readPtr(node + ptrSize)
            val name = if (lName != 0L) readCString(lName) else null
            if (!name.isNullOrEmpty()) linkBases[name.substringAfterLast('/')] = lAddr
            linkTail = node
            node = readPtr(node + 3 * ptrSize)
        }
    }

    private fun readPtr(addr: Long): Long {
        val b = readMemory(addr, ptrSize) ?: return 0L
        return leN(b, 0, ptrSize)
    }

    fun armRaw(addr: Long) { if (rawBreakpoints.add(addr)) setBp(addr) }

    fun interruptForArming() {
        val latch = CountDownLatch(1)
        armingLatch = latch
        rsp.sendRaw(0x03)
        latch.await(5, TimeUnit.SECONDS)
    }

    fun resumeSilently() { rawStopped = false; rsp.post("c") }

    fun readCString(addr: Long, max: Int = 256): String? {
        val bytes = readMemory(addr, max) ?: return null
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun closeResources() {
        runCatching { rsp.close() }
        runCatching { server.close() }
        DeviceBridge.removeForward(device.serial, localPort)
        events.shutdownNow()
    }

    private val hwBreakpoints = HashSet<Long>()
    private val swBreakpoints = HashSet<Long>()

    private fun setBp(addr: Long) {
        val a = addr.toString(16)
        if (rsp.command("Z0,$a,$bpKind") == "OK") { hwBreakpoints.remove(addr); swBreakpoints.add(addr); return }
        if (rsp.command("Z1,$a,$bpKind") == "OK") { hwBreakpoints.add(addr); swBreakpoints.remove(addr) }
    }
    private fun clearBp(addr: Long) {
        val a = addr.toString(16)
        swBreakpoints.remove(addr)
        if (hwBreakpoints.remove(addr)) rsp.command("z1,$a,$bpKind") else rsp.command("z0,$a,$bpKind")
    }

    override fun resume() {
        rawStopped = false
        val pc = readRegister(pcReg)
        val here = (breakpoints.values + rawBreakpoints).filter { it == pc }.distinct()
        if (here.isNotEmpty()) {
            here.forEach { runCatching { clearBp(it) } }
            rsp.command("vCont;s:${threadHex()}")
            here.forEach { runCatching { setBp(it) } }
        }
        rsp.post("c")
        publish(DebugState.Running)
    }
    override fun pause() { rsp.sendRaw(0x03) }
    override fun stepInto() {
        val pc = readRegister(pcReg)
        val here = (breakpoints.values + rawBreakpoints).filter { it == pc }.distinct()
        here.forEach { runCatching { clearBp(it) } }
        val r = rsp.command("vCont;s:${threadHex()}")
        here.forEach { runCatching { setBp(it) } }
        onStop(r, fromStep = true)
    }
    override fun stepOver() {
        val pc = readRegister(pcReg)
        val code = readMemory(pc, 16)
        val insn = code?.let { CapstoneDisassembler.disassemble(it, pc, arch, thumbAt(), true).firstOrNull() }
        if (insn == null || !isCall(insn.mnemonic)) { stepInto(); return }
        stepToReturn(pc, pc + insn.size)
    }

    private fun stepToReturn(pc: Long, ret: Long) {
        val here = (breakpoints.values + rawBreakpoints).filter { it == pc }.distinct()
        here.forEach { runCatching { clearBp(it) } }
        stepRearm = here
        if (ret !in rawBreakpoints && ret !in breakpoints.values) { setBp(ret); runToAddr = ret }
        rawStopped = false
        rsp.post("c")
        publish(DebugState.Running)
    }
    override fun runToCursorNative(nativeId: String, fileOffset: Long) {
        val addr = resolver.runtimeAddr(nativeId, fileOffset) ?: return
        if (addr in breakpoints.values || addr in rawBreakpoints) { resume(); return }
        runToAddr = addr
        setBp(addr)
        resume()
    }

    override fun stepOut() {
        val ret = unwind().getOrNull(1)?.first ?: run { stepInto(); return }
        stepToReturn(readRegister(pcReg), ret)
    }

    private fun thumbAt(): Boolean = arch == ElfArch.ARM && (readRegister(flagsReg) shr 5) and 1L == 1L

    private fun isCall(mnemonic: String): Boolean = io.github.nitanmarcel.jdex.disasm.Mnemonics.isCall(mnemonic)

    override fun detach() {
        breakpoints.values.forEach { runCatching { clearBp(it) } }
        runCatching { rsp.command("D") }
        closeResources()
        publish(DebugState.Detached)
    }

    override fun threads(): List<ThreadInfo> {
        val ids = ArrayList<Long>()
        var page = rsp.command("qfThreadInfo")
        while (page.startsWith("m")) {
            page.substring(1).split(',').forEach { it.toLongOrNull(16)?.let(ids::add) }
            page = rsp.command("qsThreadInfo")
        }
        val cur = currentThreadId()
        val info = runCatching { DeviceBridge.readThreadInfo(device.serial, pkg, pid) }.getOrDefault(emptyMap())
        return ids.map {
            val e = info[it]
            val name = e?.first?.let { n -> "$n ($it)" } ?: "tid $it"
            ThreadInfo(it, name, threadState(e?.second ?: ""), it == cur)
        }
    }

    private fun threadState(s: String): String = when (s.firstOrNull()) {
        'R' -> "running"; 'S' -> "sleeping"; 'D' -> "uninterruptible"
        'T' -> "stopped"; 't' -> "traced"; 'Z' -> "zombie"; 'X' -> "dead"
        else -> "stopped"
    }

    override fun currentThreadId(): Long =
        rsp.command("qC").removePrefix("QC").toLongOrNull(16) ?: 0L

    private fun unwind(): List<Pair<Long, Long>> {
        val regs = readAllRegs()
        val pc = regs[pcReg]?.toLong() ?: 0L
        var fp = regs[fpReg]?.toLong() ?: 0L
        val out = ArrayList<Pair<Long, Long>>()
        out.add(pc to fp)
        val seen = HashSet<Long>()
        var i = 1
        while (fp != 0L && fp !in seen && i < 64) {
            seen.add(fp)
            val m = readMemory(fp, ptrSize * 2) ?: break
            val savedFp = leN(m, 0, ptrSize)
            val ret = leN(m, ptrSize, ptrSize)
            if (ret == 0L) break
            out.add(ret to savedFp)
            if (savedFp <= fp) break
            fp = savedFp
            i++
        }
        return out
    }

    override fun frames(threadId: Long): List<Frame> =
        unwind().mapIndexed { i, (pc, _) -> Frame(i, frameDesc(pc), locationOf(pc)) }

    override fun variables(threadId: Long, frameIndex: Int): List<DebugVar> {
        if (frameIndex > 0) {
            val (pc, fp) = unwind().getOrNull(frameIndex) ?: return emptyList()
            return listOf(
                DebugVar("frame $frameIndex", "", "outer frame — only pc/fp recovered (GP regs = frame 0)", available = false),
                regVar(pcReg, pc), regVar(fpReg, fp),
            )
        }
        val map = decodeG(regInfos, rsp.command("g")).toMap()
        val byName = regInfos.associateBy { it.name }
        val out = ArrayList<DebugVar>()
        out += jniArgVars(map)
        keyRegs.forEach { n -> map[n]?.let { out += regVar(n, it.toLong(), "r:$n", byName[n]) } }
        regGroupRefs.clear()
        var gi = 1
        groupedRegs().forEach { (setName, regs) ->
            val ref = -(gi++).toLong()
            regGroupRefs[ref] = setName
            out += DebugVar(setName, "", "${regs.size} registers", ref = ref)
        }
        return out
    }

    private val keyRegs: Set<String> =
        regInfos.filter { it.generic in setOf("pc", "sp", "fp", "ra", "flags") }.map { it.name }.toSet() +
            setOf(pcReg, fpReg, flagsReg)

    private fun shownInGroups(r: RegInfo): Boolean =
        r.name !in keyRegs && !(r.isSub && r.encoding != "ieee754")

    private fun groupedRegs(): Map<String, List<RegInfo>> =
        regInfos.filter { shownInGroups(it) }.groupBy { it.set.ifEmpty { "Registers" } }

    private val regGroupRefs = HashMap<Long, String>()

    private fun regVar(name: String, value: Long, editKey: String? = null, info: RegInfo? = null): DebugVar {
        val hex = "0x" + value.toULong().toString(16)
        if (info?.encoding == "ieee754") {
            val f = if (info.bitsize == 32) Float.fromBits(value.toInt()).toString() else Double.fromBits(value).toString()
            return DebugVar(name, "", "$f  $hex", ref = 0L, editKey = editKey, editValue = if (editKey != null) hex else null)
        }
        val sym = annotate(value)
        return DebugVar(
            name = name, type = "",
            value = (if (DebugFormat.hex) hex else value.toString()) + (sym?.let { "  $it" } ?: ""),
            ref = if (looksPointer(value)) value else 0L,
            editKey = editKey, editValue = if (editKey != null) hex else null,
        )
    }

    private fun jniArgVars(regs: Map<String, ULong>): List<DebugVar> {
        val pc = regs[pcReg]?.toLong() ?: return emptyList()
        val (id, vaddr) = resolver.resolve(pc) ?: return emptyList()
        val (sig, isStatic) = jniSig(id, vaddr) ?: return emptyList()
        val params = io.github.nitanmarcel.jdex.disasm.JniDescriptor.paramKinds(sig) ?: return emptyList()
        return if (is64) jniArgVars64(regs, params, isStatic) else jniArgVars32(regs, params, isStatic)
    }

    private fun jniArgVars64(regs: Map<String, ULong>, params: List<Char>, isStatic: Boolean): List<DebugVar> {
        val gp = when (arch) {
            ElfArch.ARM64 -> listOf("x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7")
            ElfArch.X86_64 -> listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9")
            else -> return emptyList()
        }
        val fp = if (arch == ElfArch.ARM64) (0..7).map { "v$it" } else (0..7).map { "xmm$it" }
        var gi = 0
        var fi = 0
        fun next(isFp: Boolean): ULong? = (if (isFp) fp.getOrNull(fi++) else gp.getOrNull(gi++))?.let { regs[it] }
        val out = ArrayList<DebugVar>()
        out += DebugVar("env", "JNIEnv*", "0x" + (next(false) ?: 0uL).toString(16))
        next(false)?.let { out += DebugVar(if (isStatic) "clazz" else "this", if (isStatic) "jclass" else "jobject", jniValue('L', it)) }
        params.forEachIndexed { i, k ->
            val raw = next(k == 'F' || k == 'D') ?: return@forEachIndexed
            out += DebugVar("arg$i", jniTypeName(k), jniValue(k, raw), ref = if (k == 'L' && looksPointer(raw.toLong())) raw.toLong() else 0L)
        }
        return out
    }

    private fun jniArgVars32(regs: Map<String, ULong>, params: List<Char>, isStatic: Boolean): List<DebugVar> {
        val sp = regs[spReg]?.toLong() ?: return emptyList()
        fun slotValue(i: Int): ULong? = when {
            arch == ElfArch.ARM && i < 4 -> regs["r$i"]
            arch == ElfArch.ARM -> readMemory(sp + (i - 4) * 4L, 4)?.let { leN(it, 0, 4).toULong() }
            else -> readMemory(sp + 4L + i * 4L, 4)?.let { leN(it, 0, 4).toULong() }
        }
        var slot = 0
        fun next(wide: Boolean): ULong? {
            if (wide && arch == ElfArch.ARM && slot % 2 == 1) slot++
            val lo = slotValue(slot) ?: return null
            return if (wide) { val hi = slotValue(slot + 1) ?: 0uL; slot += 2; (hi shl 32) or (lo and 0xFFFFFFFFuL) }
            else { slot++; lo }
        }
        val out = ArrayList<DebugVar>()
        out += DebugVar("env", "JNIEnv*", "0x" + (next(false) ?: 0uL).toString(16))
        next(false)?.let { out += DebugVar(if (isStatic) "clazz" else "this", if (isStatic) "jclass" else "jobject", jniValue('L', it)) }
        params.forEachIndexed { i, k ->
            val raw = next(k == 'J' || k == 'D') ?: return@forEachIndexed
            out += DebugVar("arg$i", jniTypeName(k), jniValue(k, raw), ref = if (k == 'L' && looksPointer(raw.toLong())) raw.toLong() else 0L)
        }
        return out
    }

    private fun jniTypeName(k: Char): String = when (k) {
        'Z' -> "jboolean"; 'B' -> "jbyte"; 'C' -> "jchar"; 'S' -> "jshort"
        'I' -> "jint"; 'J' -> "jlong"; 'F' -> "jfloat"; 'D' -> "jdouble"; else -> "jobject"
    }

    private fun jniValue(k: Char, raw: ULong): String = when (k) {
        'Z' -> if (raw.toLong() and 1L != 0L) "true" else "false"
        'C' -> "'${raw.toInt().toChar()}'"
        'F' -> Float.fromBits(raw.toInt()).toString()
        'D' -> Double.fromBits(raw.toLong()).toString()
        'J' -> if (DebugFormat.hex) "0x" + raw.toString(16) else raw.toLong().toString()
        'B', 'S', 'I' -> { val n = if (k == 'B') raw.toByte().toLong() else if (k == 'S') raw.toShort().toLong() else raw.toInt().toLong(); if (DebugFormat.hex) "0x" + n.toString(16) else n.toString() }
        else -> "0x" + raw.toString(16) + (annotate(raw.toLong())?.let { "  $it" } ?: "")
    }

    override fun children(ref: Long): List<DebugVar> {
        regGroupRefs[ref]?.let { setName ->
            val map = decodeG(regInfos, rsp.command("g")).toMap()
            return regInfos.filter { shownInGroups(it) && it.set.ifEmpty { "Registers" } == setName }
                .mapNotNull { ri -> map[ri.name]?.let { regVar(ri.name, it.toLong(), "r:${ri.name}", ri) } }
        }
        val n = 8
        val bytes = readMemory(ref, n * ptrSize) ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            if ((i + 1) * ptrSize > bytes.size) return@mapNotNull null
            val w = leN(bytes, i * ptrSize, ptrSize)
            val sym = annotate(w)
            DebugVar(
                name = "[+0x${(i * ptrSize).toString(16)}]", type = "",
                value = "0x" + w.toULong().toString(16) + (sym?.let { "  $it" } ?: ""),
                ref = if (looksPointer(w)) w else 0L,
            )
        }
    }

    private fun annotate(addr: Long): String? {
        val loc = resolver.resolve(if (is64) addr and 0x00FFFFFFFFFFFFFFL else addr) ?: return null
        nativeSymbol(loc.first, loc.second)?.let { return "${loc.first}!$it" }
        stringPreview(addr)?.let { return it }
        return "${loc.first}!0x${loc.second.toString(16)}"
    }

    private fun stringPreview(addr: Long): String? {
        val bytes = readMemory(addr, 64) ?: return null
        val nul = bytes.indexOf(0.toByte())
        if (nul < 2) return null
        val s = bytes.copyOf(nul)
        if (!s.all { it.toInt() in 0x20..0x7e }) return null
        return "\"" + String(s, Charsets.US_ASCII) + "\""
    }

    private fun looksPointer(v: Long): Boolean {
        val u = (if (is64) v and 0x00FFFFFFFFFFFFFFL else v).toULong()
        val upper = when {
            !is64 -> 0x1_0000_0000uL
            arch == ElfArch.X86_64 -> 0x0000_8000_0000_0000uL
            else -> 0x0001_0000_0000_0000uL
        }
        return u >= 0x10000uL && u < upper
    }

    override fun looksLikePointer(address: Long): Boolean = looksPointer(address)

    override fun architecturalPc(): Long? {
        val pc = readRegister(pcReg).takeIf { it != 0L } ?: return null
        return when (arch) {
            ElfArch.X86, ElfArch.X86_64 -> {
                val code = readMemory(pc, 16)
                val size = code?.let { CapstoneDisassembler.disassemble(it, pc, arch, false, true).firstOrNull()?.size } ?: 0
                pc + size
            }
            ElfArch.ARM -> pc + if (thumbAt()) 4 else 8
            else -> pc
        }
    }

    override fun modules(): List<LoadedModule> {
        refreshModules()
        return resolver.all().map { LoadedModule(it.path.substringAfterLast('/'), it.path, it.base, it.end - it.base) }
    }

    override fun inlineValues(): Map<String, String> =
        decodeG(regInfos, rsp.command("g")).associate { (name, v) -> name to "0x" + v.toString(16) }

    override fun readMemory(address: Long, length: Int): ByteArray? {
        val a = if (is64) address and 0x00FFFFFFFFFFFFFFL else address
        val r = rsp.command("m${a.toULong().toString(16)},${length.toString(16)}")
        if (r.isEmpty() || r.startsWith("E")) return null
        return ByteArray(r.length / 2) { r.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    override fun writeMemory(address: Long, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val a = if (is64) address and 0x00FFFFFFFFFFFFFFL else address
        val hex = bytes.joinToString("") { "%02x".format(it) }
        var ok = false
        whileStopped { ok = rsp.command("M${a.toULong().toString(16)},${bytes.size.toString(16)}:$hex") == "OK" }
        return ok
    }

    private inline fun whileStopped(action: () -> Unit) {
        if (current == DebugState.Running && !rawStopped) {
            interruptForArming(); action(); resumeSilently()
        } else action()
    }

    override fun addBreakpoint(bp: Breakpoint) {
        if (bp !is Breakpoint.Native) return
        val addr = resolver.runtimeAddr(bp.nativeId, bp.fileOffset) ?: return
        whileStopped { setBp(addr) }
        breakpoints[bp] = addr
    }

    override fun removeBreakpoint(bp: Breakpoint) {
        if (bp !is Breakpoint.Native) return
        val addr = breakpoints.remove(bp) ?: return
        whileStopped { clearBp(addr) }
    }

    override fun setValue(editKey: String, text: String): Boolean {
        if (!editKey.startsWith("r:")) return false
        val name = editKey.removePrefix("r:")
        val v = text.trim().removePrefix("0x").toULongOrNull(16) ?: text.trim().toULongOrNull() ?: return false
        return writeRegister(name, v.toLong())
    }

    companion object {
        private fun awaitListening(serial: String, port: Int) {
            val portHex = "%04X".format(port)
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                val tcp = DeviceBridge.shell(serial, "cat /proc/net/tcp /proc/net/tcp6")
                if (tcp.lineSequence().any {
                        val c = it.trim().split(Regex("\\s+"))
                        c.size > 3 && c[1].endsWith(":$portHex") && c[3] == "0A"
                    }) return
                Thread.sleep(150)
            }
        }

        fun attach(device: DebugDevice, pkg: String, pid: Int, config: NativeDebug): NativeSession {
            var server: AutoCloseable = AutoCloseable {}
            val host: String
            val port: Int
            when (config) {
                is NativeDebug.Managed -> {
                    port = 5039
                    host = "127.0.0.1"
                    runCatching { DeviceBridge.shell(device.serial, "run-as $pkg pkill lldb-server") }
                    DeviceBridge.removeForward(device.serial, port)
                    server = DeviceBridge.startLldbServer(device.serial, pkg, config.lldbServerPath, pid, port)
                    DeviceBridge.forward(device.serial, port, port)
                    awaitListening(device.serial, port)
                }
                is NativeDebug.Remote -> { host = config.host; port = config.port }
            }
            var rsp: RspClient? = null
            try {
                val client = RspClient(host, port).also { rsp = it }
                client.connect()
                runCatching { client.command("QPassSignals:03;0b;17;21;22;23;24") }
                val target = TargetDescription.discover(client)
                val maps = DeviceBridge.readProcMaps(device.serial, pkg, pid)
                val resolver = ModuleResolver(parseMaps(maps))
                val session = NativeSession(device, pid, pkg, client, server, port, target.registers, resolver, target.arch)
                client.onStop = session::onStop
                session.current = DebugState.Stopped(session.pcToLocation())
                return session
            } catch (e: Throwable) {
                runCatching { rsp?.close() }
                runCatching { server.close() }
                if (config is NativeDebug.Managed) DeviceBridge.removeForward(device.serial, port)
                throw e
            }
        }
    }
}
