package io.github.nitanmarcel.jdex.project

import jadx.api.JadxDecompiler
import org.graalvm.polyglot.Value

class ScriptApi(
    private val session: () -> ApkSession?,
    private val renameStore: () -> RenameStore = { NoRenames },
    private val onChanged: () -> Unit = {},
    private val dexStore: () -> DexStore = { NoDexStore },
    private val onReanalyze: () -> Unit = {},
    private val fileImporter: (String, ByteArray) -> Unit = { _, _ -> },
    private val ui: ScriptUi = NoScriptUi,
    private val debug: DebugControl = NoDebugControl,
    private val emu: EmuControl = NoEmuControl,
    private val thisEmu: EmuControl = NoEmuControl,
    private val nativeEmu: NativeEmuControl = NoNativeEmuControl,
    private val configOf: () -> EngineConfig = { EngineConfig.DEFAULT },
    private val applyConfig: (EngineConfig) -> Unit = {},
) {

    private fun active(): ApkSession = session() ?: throw IllegalStateException("No APK or DEX loaded")

    private fun malformed(sha: String): MalformedDex? = active().malformedDexes.firstOrNull { it.sha == sha }

    private fun rawOf(desc: String): String = desc.substringBefore("->").removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun descOf(raw: String): String = "L" + raw.replace('.', '/') + ";"

    fun classDescriptors(): List<String> = active().classRawNames().map { descOf(it) }

    fun hasClass(desc: String): Boolean = active().classNode(rawOf(desc)) != null

    fun classMethods(desc: String): List<String> = active().members(rawOf(desc)).methods.map { "$desc->${it.shortId}" }

    fun classFields(desc: String): List<String> = active().members(rawOf(desc)).fields.map { "$desc->${it.name}" }

    fun classJava(desc: String): String = active().javaSource(rawOf(desc)) ?: ""

    fun classSmali(desc: String): String = active().smali(rawOf(desc)) ?: ""

    fun configEmulatorEnabled(): Boolean = configOf().emulatorEnabled

    fun configDecryptStringsAtStartup(): Boolean = configOf().decryptStringsAtStartup

    fun configCodeCleanup(): Boolean = configOf().codeCleanup

    fun configPasses(): List<String> = configOf().passes.map { it.id }

    fun configAllPasses(): List<String> = EnginePass.entries.map { it.id }

    fun configApply(emulator: Boolean, decryptStartup: Boolean, cleanup: Boolean, passes: List<Any?>) {
        val ids = passes.mapNotNull { it?.toString() }.toSet()
        val ps = EnginePass.entries.filter { it.id in ids }.toSet()
        applyConfig(EngineConfig(emulator, decryptStartup, ps, cleanup))
    }

    fun classSuper(desc: String): String? = active().classSuper(rawOf(desc))

    fun classInterfaces(desc: String): List<String> = active().classInterfaces(rawOf(desc))

    fun findMethods(pattern: String): List<String> = active().findMethods(pattern)

    fun findFields(pattern: String): List<String> = active().findFields(pattern)

    fun methodSmali(desc: String): String? = active().methodSmali(rawOf(desc), desc.substringAfter("->"))

    fun methodInstructions(desc: String): List<Map<String, Any?>>? =
        active().methodInstructions(rawOf(desc), desc.substringAfter("->"))

    fun classStrings(desc: String): List<String> = active().classStrings(rawOf(desc))

    fun classInfo(desc: String): Map<String, Any?>? = active().classInfo(rawOf(desc))

    fun methodInfo(desc: String): Map<String, Any?>? = active().methodInfo(rawOf(desc), desc.substringAfter("->"))

    fun fieldInfo(desc: String): Map<String, Any?>? =
        active().fieldInfo(rawOf(desc), desc.substringAfter("->").substringBefore(':'))

    fun searchCode(pattern: String, limit: Int): List<Map<String, Any?>> = active().searchCode(pattern, limit)

    fun allStrings(): List<Map<String, Any?>> = active().allStrings()

    fun fieldReads(desc: String): List<String> = active().fieldAccess(desc, write = false)

    fun fieldWrites(desc: String): List<String> = active().fieldAccess(desc, write = true)

    fun offsetAtLine(desc: String, line: Int): Int? = active().offsetAtLine(rawOf(desc), line)

    fun xrefsTo(desc: String): List<String> {
        val kind = when {
            "->" !in desc -> SymbolKind.TYPE
            '(' in desc.substringAfter("->") -> SymbolKind.METHOD
            else -> SymbolKind.FIELD
        }
        val usages = active().usages(Symbol(kind, desc)) ?: return emptyList()
        return usages.map { u ->
            val cls = descOf(u.rawName)
            when {
                u.shortId != null -> "$cls->${u.shortId}"
                u.fieldName != null -> "$cls->${u.fieldName}"
                else -> cls
            }
        }
    }

    fun manifest(): String = active().manifest()

    fun permissions(): List<String> = active().permissions()

    fun components(tag: String): List<String> = active().components(tag)

    fun appPackage(): String? = active().appPackage

    fun mainActivity(): String? = active().mainActivity()

    fun dexShas(): List<String> = active().malformedDexes.map { it.sha }

    fun dexName(sha: String): String = malformed(sha)?.name ?: ""

    fun dexProblems(sha: String): List<String> = malformed(sha)?.problems ?: emptyList()

    fun dexBytes(sha: String): ByteArray = malformed(sha)?.effective ?: ByteArray(0)

    fun dexSourceBytes(sha: String): ByteArray = malformed(sha)?.source ?: ByteArray(0)

    fun dexMalformed(sha: String): Boolean = malformed(sha)?.let { Dex.parseBroken(it.effective) } ?: false

    fun validateDex(bytes: ByteArray): List<String> = Dex.validate(bytes)

    fun repairDex(sha: String): Boolean {
        val md = malformed(sha) ?: return false
        val repaired = Dex.repair(md.effective)
        dexStore().savePatch(sha, DexPatch.between(md.source, repaired))
        onReanalyze()
        return !Dex.parseBroken(repaired)
    }

    fun saveDex(sha: String, bytes: ByteArray) {
        val md = malformed(sha) ?: return
        dexStore().savePatch(sha, DexPatch.between(md.source, bytes))
        onReanalyze()
    }

    fun rename(key: String, name: String?) {
        renameStore().setRename(key, name?.takeIf { it.isNotBlank() })
        onChanged()
    }

    fun setAnnotation(descriptor: String, offset: Int, text: String?) = active().setAnnotation(descriptor, offset, text)

    fun clearAnnotations() = active().annotations.clear()

    fun fileNames(): List<String> = active().entryNames()

    fun readFile(path: String): ByteArray = active().readFile(path)

    fun importFile(name: String, bytes: ByteArray) = fileImporter(name, bytes)

    fun debugDevices(): List<Map<String, Any>> =
        io.github.nitanmarcel.jdex.debug.DeviceBridge.devices()
            .map { mapOf("serial" to it.serial, "label" to it.label, "online" to it.online) }

    fun debugProcesses(serial: String): List<Map<String, Any>> =
        io.github.nitanmarcel.jdex.debug.DeviceBridge.processes(serial)
            .map { mapOf("pid" to it.pid, "name" to it.name) }

    fun debugAttach(serial: String, pid: Int): Boolean = debug.attach(serial, pid)

    fun debugDetach() = debug.detach()

    fun debugResume() = debug.resume()

    fun debugPause() = debug.pause()

    fun debugStepInto() = debug.stepInto()

    fun debugStepOver() = debug.stepOver()

    fun debugStepOut() = debug.stepOut()

    fun debugSetBreakpoint(descriptor: String, dexPc: Int) = debug.setBreakpoint(descriptor, dexPc)

    fun debugClearBreakpoint(descriptor: String, dexPc: Int) = debug.clearBreakpoint(descriptor, dexPc)

    fun debugState(): String = debug.state()

    fun debugFrames(): List<Map<String, Any?>> = debug.frames()

    fun debugVariables(frameIndex: Int): List<Map<String, Any?>> = debug.variables(frameIndex)

    fun debugReadMemory(address: Long, length: Int): ByteArray? = debug.readMemory(address, length)

    fun debugWriteMemory(address: Long, bytes: ByteArray): Boolean = debug.writeMemory(address, bytes)

    fun debugRuntimeAddr(nativeId: String, vaddr: Long): Long? = debug.runtimeAddr(nativeId, vaddr)

    fun debugPatchNative(nativeId: String, vaddr: Long, asm: String): Boolean = debug.patchNative(nativeId, vaddr, asm)

    fun assemble(asm: String, arch: String, address: Long): ByteArray? =
        runCatching { io.github.nitanmarcel.jdex.disasm.ElfArch.valueOf(arch.uppercase()) }.getOrNull()
            ?.let { io.github.nitanmarcel.jdex.disasm.KeystoneAssembler.assemble(asm, address, it).getOrNull() }

    fun uiMessage(text: String, error: Boolean) = ui.message(text, error)

    fun uiInput(prompt: String, default: String): String? = ui.input(prompt, default)

    fun uiConfirm(text: String): Boolean = ui.confirm(text)

    fun uiGotoOffset(offset: Long) = ui.gotoOffset(offset)

    fun uiOpen(desc: String) = ui.open(desc)

    private fun emuCtl(active: Boolean): EmuControl = if (active) thisEmu else emu

    fun emuRun(active: Boolean, descriptor: String, args: List<Any?>, pauseAtEntry: Boolean): Boolean = emuCtl(active).run(descriptor, args, pauseAtEntry)
    fun emuDetach(active: Boolean) = emuCtl(active).detach()
    fun emuResume(active: Boolean) = emuCtl(active).resume()
    fun emuPause(active: Boolean) = emuCtl(active).pause()
    fun emuStepInto(active: Boolean) = emuCtl(active).stepInto()
    fun emuStepOver(active: Boolean) = emuCtl(active).stepOver()
    fun emuStepOut(active: Boolean) = emuCtl(active).stepOut()
    fun emuSetBreakpoint(active: Boolean, descriptor: String, dexPc: Int) = emuCtl(active).setBreakpoint(descriptor, dexPc)
    fun emuClearBreakpoint(active: Boolean, descriptor: String, dexPc: Int) = emuCtl(active).clearBreakpoint(descriptor, dexPc)
    fun emuRunToCursor(active: Boolean, descriptor: String, dexPc: Int) = emuCtl(active).runToCursor(descriptor, dexPc)
    fun emuState(active: Boolean): String = emuCtl(active).state()
    fun emuFrames(active: Boolean): List<Map<String, Any?>> = emuCtl(active).frames()
    fun emuVariables(active: Boolean, frameIndex: Int): List<Map<String, Any?>> = emuCtl(active).variables(frameIndex)
    fun emuChildren(active: Boolean, ref: Long): List<Map<String, Any?>> = emuCtl(active).children(ref)
    fun emuSetValue(active: Boolean, editKey: String, text: String): Boolean = emuCtl(active).setValue(editKey, text)
    fun emuSetRegister(active: Boolean, frameIndex: Int, reg: Int, value: Any?): Boolean = emuCtl(active).setRegister(frameIndex, reg, value)
    fun emuReturnValue(active: Boolean): Any? = emuCtl(active).returnValue()
    fun emuResolve(active: Boolean, descriptor: String, args: List<Any?>?): Map<String, Any?> = emuCtl(active).resolve(descriptor, args)
    fun emuRegisterField(active: Boolean, classDesc: String, name: String, value: Any?) = emuCtl(active).registerField(classDesc, name, value)
    fun emuRegisterStub(active: Boolean, classDesc: String, name: String, fn: Value) =
        emuCtl(active).registerStub(classDesc, name) { recv, args -> pyToJvm(fn.execute(recv, args)) }
    fun emuAwaitStop(active: Boolean, timeoutMs: Long): Boolean = emuCtl(active).awaitStop(timeoutMs)
    fun emuAwaitFinished(active: Boolean, timeoutMs: Long): Boolean = emuCtl(active).awaitFinished(timeoutMs)

    fun emuRunning(active: Boolean): Boolean = emuCtl(active).state() != "detached"
    fun debugRunning(): Boolean = debug.state() != "detached"

    fun emuHook(active: Boolean, descriptor: String, cb: Value): Int =
        emuCtl(active).hook(descriptor) { call -> cb.execute(HookCallView(call, ::pyToJvm)) }
    fun emuUnhook(active: Boolean, id: Int): Boolean = emuCtl(active).unhook(id)
    fun emuHooksList(active: Boolean): List<Map<String, Any?>> = emuCtl(active).installedHooks()
    fun emuClearHooks(active: Boolean) = emuCtl(active).clearHooks()

    fun nativeEmuRun(lib: String, className: String, methodSig: String, args: List<Any?>): Boolean = nativeEmu.run(lib, className, methodSig, args.map(::nativeArg))

    fun nativeEmuLoad(lib: String): Boolean = nativeEmu.load(lib)
    fun nativeEmuMalloc(size: Int): Long = nativeEmu.malloc(size)
    fun nativeEmuMemRead(address: Long, size: Int): ByteArray? = nativeEmu.memRead(address, size)
    fun nativeEmuRegRead(name: String): Long? = nativeEmu.regRead(name)
    fun nativeEmuCall(address: Long, args: List<Any?>): Any? = nativeEmu.call(address, args.map(::nativeArg))
    fun nativeEmuEmulate(begin: Long, until: Long): Any? = nativeEmu.emulate(begin, until)

    fun nativeEmuDecrypt(lib: String, className: String, methodSig: String, cipher: Any?): String? = nativeEmu.decrypt(lib, className, methodSig, nativeArg(cipher))

    fun nativeEmuDetach() = nativeEmu.detach()
    fun nativeEmuResume() = nativeEmu.resume()
    fun nativeEmuStepInto() = nativeEmu.stepInto()
    fun nativeEmuStepOver() = nativeEmu.stepOver()
    fun nativeEmuStepOut() = nativeEmu.stepOut()
    fun nativeEmuSetBreakpoint(address: Long) = nativeEmu.setBreakpoint(address)
    fun nativeEmuClearBreakpoint(address: Long) = nativeEmu.clearBreakpoint(address)
    fun nativeEmuSymbolAddress(name: String): Long? = nativeEmu.symbolAddress(name)
    fun nativeEmuModuleBase(): Long = nativeEmu.moduleBase()
    fun nativeEmuState(): String = nativeEmu.state()
    fun nativeEmuFrames(): List<Map<String, Any?>> = nativeEmu.frames()
    fun nativeEmuRegisters(): Map<String, Long> = nativeEmu.registers()
    fun nativeEmuSetRegister(name: String, value: Long): Boolean = nativeEmu.setRegister(name, value)
    fun nativeEmuWriteMemory(address: Long, bytes: Any?): Boolean = nativeEmu.writeMemory(address, nativeArg(bytes) as? ByteArray ?: return false)
    fun nativeEmuPatch(fileOffset: Long, asm: String): Boolean = nativeEmu.patch(fileOffset, asm)
    fun nativeEmuReturnValue(): Any? = nativeEmu.returnValue()
    fun nativeEmuAwaitStop(timeoutMs: Long): Boolean = nativeEmu.awaitStop(timeoutMs)
    fun nativeEmuAwaitFinished(timeoutMs: Long): Boolean = nativeEmu.awaitFinished(timeoutMs)

    fun nativeEmuOnSyscall(cb: Value): Boolean = nativeEmu.onSyscall { ctx ->
        val r = cb.execute(NativeHookView(ctx, ::nativeArg))
        if (r == null || r.isNull) false else if (r.isBoolean) r.asBoolean() else true
    }
    fun nativeEmuClearSyscall() = nativeEmu.clearSyscall()
    fun nativeEmuHook(address: Long, onEnter: Value?, onLeave: Value?): Int = nativeEmu.hook(
        address,
        onEnter?.let { v -> { ctx: io.github.nitanmarcel.jdex.disasm.NativeHookContext -> v.execute(NativeHookView(ctx, ::nativeArg)); Unit } },
        onLeave?.let { v -> { ctx: io.github.nitanmarcel.jdex.disasm.NativeHookContext -> v.execute(NativeHookView(ctx, ::nativeArg)); Unit } },
    )
    fun nativeEmuReplace(address: Long, cb: Value): Int = nativeEmu.replace(address) { ctx ->
        val r = cb.execute(NativeHookView(ctx, ::nativeArg))
        if (r == null || r.isNull) null else r.asLong()
    }
    fun nativeEmuUnhook(id: Int): Boolean = nativeEmu.unhook(id)
    fun nativeEmuMemWatch(begin: Long, end: Long, onRead: Value?, onWrite: Value?): Int = nativeEmu.memWatch(
        begin, end,
        onRead?.let { v -> { a: io.github.nitanmarcel.jdex.disasm.NativeMemAccess -> v.execute(NativeMemAccessView(a, ::nativeArg)); Unit } },
        onWrite?.let { v -> { a: io.github.nitanmarcel.jdex.disasm.NativeMemAccess -> v.execute(NativeMemAccessView(a, ::nativeArg)); Unit } },
    )
    fun nativeEmuTrace(begin: Long, end: Long, cb: Value): Int {
        val b = if (begin == 0L && end == 0L) 1L else begin
        return nativeEmu.trace(b, end) { a -> cb.execute(NativeMemAccessView(a, ::nativeArg)); Unit }
    }
    fun nativeEmuModules(): List<Map<String, Any?>> = nativeEmu.modules()
    fun nativeEmuSymbolAt(address: Long): Map<String, Any?>? = nativeEmu.symbolAt(address)

    private fun nativeArg(v: Any?): Any? = when (v) {
        is Value -> if (v.hasArrayElements()) ByteArray(v.arraySize.toInt()) { v.getArrayElement(it.toLong()).asInt().toByte() } else pyToJvm(v)
        is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
        else -> v
    }

    private fun pyToJvm(v: Value): Any? = pyToEmu(v, null)

    fun emuNew(active: Boolean, classDesc: String, ctorSig: String?, args: List<Any?>): EmuObject? =
        emuCtl(active).newObject(classDesc, ctorSig, args)
    fun emuIsObject(v: Any?): Boolean = isEmuObject(v)
    fun emuObjType(o: EmuObject): String = o.type
    fun emuObjGet(o: EmuObject, name: String): Any? = emuObjFriendly(o.get(name))
    fun emuObjSet(o: EmuObject, name: String, value: Any?) = o.set(name, value)
    fun emuObjCall(o: EmuObject, shortId: String, args: List<Any?>): Any? = emuObjFriendly(o.call(shortId, args))
    fun emuObjFields(o: EmuObject): Map<String, Any?> = o.fields().mapValues { emuFieldFriendly(it.value) }

    private fun emuObjFriendly(v: Any?): Any? = if (v is io.github.nitanmarcel.jdex.exec.runtime.UnknownVal) null else v
    private fun emuFieldFriendly(v: Any?): Any? = when (v) {
        is io.github.nitanmarcel.jdex.exec.runtime.UnknownVal -> null
        is io.github.nitanmarcel.jdex.exec.runtime.DvmObject -> v.type
        else -> v
    }

    fun jadx(): JadxDecompiler = active().decompiler()
}

interface DebugControl {
    fun attach(serial: String, pid: Int): Boolean = false
    fun detach() {}
    fun resume() {}
    fun pause() {}
    fun stepInto() {}
    fun stepOver() {}
    fun stepOut() {}
    fun setBreakpoint(descriptor: String, dexPc: Int) {}
    fun clearBreakpoint(descriptor: String, dexPc: Int) {}
    fun state(): String = "detached"
    fun frames(): List<Map<String, Any?>> = emptyList()
    fun variables(frameIndex: Int): List<Map<String, Any?>> = emptyList()
    fun readMemory(address: Long, length: Int): ByteArray? = null
    fun writeMemory(address: Long, bytes: ByteArray): Boolean = false
    fun runtimeAddr(nativeId: String, vaddr: Long): Long? = null
    fun patchNative(nativeId: String, vaddr: Long, asm: String): Boolean = false
}

object NoDebugControl : DebugControl

interface EmuControl {
    fun run(descriptor: String, args: List<Any?>, pauseAtEntry: Boolean = true): Boolean = false
    fun detach() {}
    fun resume() {}
    fun pause() {}
    fun stepInto() {}
    fun stepOver() {}
    fun stepOut() {}
    fun setBreakpoint(descriptor: String, dexPc: Int) {}
    fun clearBreakpoint(descriptor: String, dexPc: Int) {}
    fun runToCursor(descriptor: String, dexPc: Int) {}
    fun state(): String = "detached"
    fun frames(): List<Map<String, Any?>> = emptyList()
    fun variables(frameIndex: Int): List<Map<String, Any?>> = emptyList()
    fun children(ref: Long): List<Map<String, Any?>> = emptyList()
    fun setValue(editKey: String, text: String): Boolean = false
    fun setRegister(frameIndex: Int, reg: Int, value: Any?): Boolean = false
    fun returnValue(): Any? = null
    fun resolve(descriptor: String, args: List<Any?>?): Map<String, Any?> = emptyMap()
    fun registerStub(classDesc: String, name: String, handler: (Any?, List<Any?>) -> Any?) {}
    fun registerField(classDesc: String, name: String, value: Any?) {}
    fun newObject(classDesc: String, ctorSig: String?, args: List<Any?>): EmuObject? = null
    fun awaitStop(timeoutMs: Long): Boolean = false
    fun awaitFinished(timeoutMs: Long): Boolean = false
    fun hook(descriptor: String, hook: io.github.nitanmarcel.jdex.exec.Interceptor): Int = 0
    fun unhook(id: Int): Boolean = false
    fun installedHooks(): List<Map<String, Any?>> = emptyList()
    fun clearHooks() {}
}

object NoEmuControl : EmuControl

class HookCallView(private val call: io.github.nitanmarcel.jdex.exec.HookCall, private val conv: (Value) -> Any?) {
    val method: String get() = call.method
    val receiver: Any? get() = call.receiver
    val args: List<Any?> get() = call.args
    fun set_arg(index: Int, value: Any?) = call.setArg(index, emuUnwrap(if (value is Value) conv(value) else value))
    fun replace(value: Any?) = call.replace(emuUnwrap(if (value is Value) conv(value) else value))
}

class NativeHookView(private val ctx: io.github.nitanmarcel.jdex.disasm.NativeHookContext, private val conv: (Value) -> Any?) {
    fun number(): Long = ctx.number
    fun arg(index: Int): Long = ctx.arg(index)
    fun set_arg(index: Int, value: Long) = ctx.setArg(index, value)
    fun ret(): Long = ctx.ret()
    fun set_ret(value: Long) = ctx.setRet(value)
    fun reg(name: String): Long = ctx.reg(name)
    fun set_reg(name: String, value: Long) = ctx.setReg(name, value)
    fun read_mem(address: Long, size: Int): ByteArray? = ctx.readMem(address, size)
    fun write_mem(address: Long, data: Any?): Boolean = toBytes(data)?.let { ctx.writeMem(address, it) } ?: false

    private fun toBytes(data: Any?): ByteArray? = when (val c = if (data is Value) conv(data) else data) {
        is ByteArray -> c
        is List<*> -> ByteArray(c.size) { (c[it] as Number).toByte() }
        is String -> c.toByteArray()
        else -> null
    }
}

class NativeMemAccessView(private val acc: io.github.nitanmarcel.jdex.disasm.NativeMemAccess, private val conv: (Value) -> Any?) {
    fun address(): Long = acc.address()
    fun size(): Int = acc.size()
    fun value(): Long = acc.value()
    fun is_write(): Boolean = acc.isWrite()
    fun reg(name: String): Long = acc.reg(name)
    fun set_reg(name: String, value: Long) = acc.setReg(name, value)
    fun read_mem(address: Long, size: Int): ByteArray? = acc.readMem(address, size)
    fun write_mem(address: Long, data: Any?): Boolean = toBytes(data)?.let { acc.writeMem(address, it) } ?: false

    private fun toBytes(data: Any?): ByteArray? = when (val c = if (data is Value) conv(data) else data) {
        is ByteArray -> c
        is List<*> -> ByteArray(c.size) { (c[it] as Number).toByte() }
        is String -> c.toByteArray()
        else -> null
    }
}

interface NativeEmuControl {
    fun run(lib: String, className: String, methodSig: String, args: List<Any?>): Boolean = false
    fun load(lib: String): Boolean = false
    fun malloc(size: Int): Long = 0L
    fun memRead(address: Long, size: Int): ByteArray? = null
    fun regRead(name: String): Long? = null
    fun call(address: Long, args: List<Any?>): Any? = null
    fun emulate(begin: Long, until: Long): Any? = null
    fun decrypt(lib: String, className: String, methodSig: String, cipher: Any?): String? = null
    fun detach() {}
    fun resume() {}
    fun stepInto() {}
    fun stepOver() {}
    fun stepOut() {}
    fun setBreakpoint(address: Long) {}
    fun clearBreakpoint(address: Long) {}
    fun symbolAddress(name: String): Long? = null
    fun moduleBase(): Long = 0L
    fun state(): String = "detached"
    fun frames(): List<Map<String, Any?>> = emptyList()
    fun registers(): Map<String, Long> = emptyMap()
    fun setRegister(name: String, value: Long): Boolean = false
    fun writeMemory(address: Long, bytes: ByteArray): Boolean = false
    fun patch(fileOffset: Long, asm: String): Boolean = false
    fun returnValue(): Any? = null
    fun awaitStop(timeoutMs: Long): Boolean = false
    fun awaitFinished(timeoutMs: Long): Boolean = false
    fun onSyscall(cb: (io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Boolean): Boolean = false
    fun clearSyscall() {}
    fun hook(address: Long, onEnter: ((io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Unit)?, onLeave: ((io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Unit)?): Int = 0
    fun replace(address: Long, cb: (io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Long?): Int = 0
    fun unhook(id: Int): Boolean = false
    fun memWatch(begin: Long, end: Long, onRead: ((io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit)?, onWrite: ((io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit)?): Int = 0
    fun trace(begin: Long, end: Long, cb: (io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit): Int = 0
    fun modules(): List<Map<String, Any?>> = emptyList()
    fun symbolAt(address: Long): Map<String, Any?>? = null
}

object NoNativeEmuControl : NativeEmuControl

interface ScriptUi {
    fun message(text: String, error: Boolean) {}
    fun input(prompt: String, default: String): String? = null
    fun confirm(text: String): Boolean = false
    fun gotoOffset(offset: Long) {}
    fun open(desc: String) {}
}

object NoScriptUi : ScriptUi
