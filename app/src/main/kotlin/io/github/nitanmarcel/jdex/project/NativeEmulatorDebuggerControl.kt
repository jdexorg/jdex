package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.debug.Breakpoint
import io.github.nitanmarcel.jdex.debug.DebugLocation
import io.github.nitanmarcel.jdex.debug.DebugState
import io.github.nitanmarcel.jdex.debug.NativeEmulatorDebugger
import io.github.nitanmarcel.jdex.disasm.ElfFile
import io.github.nitanmarcel.jdex.disasm.VmJavaBridge
import io.github.nitanmarcel.jdex.exec.MethodSource

class NativeEmulatorDebuggerControl(
    private val lib: (String) -> ByteArray?,
    private val source: () -> MethodSource?,
    private val world: () -> io.github.nitanmarcel.jdex.exec.EmuWorld? = { null },
) : NativeEmuControl {

    private var dbg: NativeEmulatorDebugger? = null
    private var currentLib: String? = null
    private var dbgSource: MethodSource? = null

    private fun debugger(libName: String): NativeEmulatorDebugger? {
        val cur = source()
        if (currentLib != libName || cur !== dbgSource) { dbg?.close(); dbg = null }
        if (dbg == null) {
            val bytes = lib(libName) ?: return null
            val elf = ElfFile.parse(bytes) ?: return null
            dbg = NativeEmulatorDebugger(bytes, elf.is64, nativeId = libName, bridge = cur?.let { VmJavaBridge(it, world = world()) })
            currentLib = libName
            dbgSource = cur
        }
        return dbg
    }

    override fun run(lib: String, className: String, methodSig: String, args: List<Any?>): Boolean {
        val d = debugger(lib) ?: return false
        d.runMethod(className, methodSig, args)
        return true
    }

    override fun load(lib: String): Boolean = debugger(lib) != null
    override fun malloc(size: Int): Long = dbg?.malloc(size) ?: 0L
    override fun memRead(address: Long, size: Int): ByteArray? = dbg?.readMemory(address, size)
    override fun regRead(name: String): Long? = dbg?.regRead(name)
    override fun call(address: Long, args: List<Any?>): Any? = dbg?.callAddress(address, args)
    override fun emulate(begin: Long, until: Long): Any? = dbg?.emulate(begin, until)

    override fun decrypt(lib: String, className: String, methodSig: String, cipher: Any?): String? {
        val d = debugger(lib) ?: return null
        d.runMethod(className, methodSig, listOfNotNull(cipher), pauseAtEntry = false)
        d.awaitFinished(10_000)
        return d.returnValue() as? String
    }

    override fun detach() { dbg?.detach() }
    override fun resume() { dbg?.resume() }
    override fun stepInto() { dbg?.stepInto() }
    override fun stepOver() { dbg?.stepOver() }
    override fun stepOut() { dbg?.stepOut() }

    override fun setBreakpoint(address: Long) { dbg?.let { it.addBreakpoint(Breakpoint.Native(currentLib ?: "", address - it.moduleBase)) } }
    override fun clearBreakpoint(address: Long) { dbg?.let { it.removeBreakpoint(Breakpoint.Native(currentLib ?: "", address - it.moduleBase)) } }
    override fun symbolAddress(name: String): Long? = dbg?.symbolAddress(name)
    override fun moduleBase(): Long = dbg?.moduleBase ?: 0L

    override fun state(): String = when (dbg?.state) {
        is DebugState.Running -> "running"
        is DebugState.Stopped -> "stopped"
        else -> "detached"
    }

    override fun frames(): List<Map<String, Any?>> = dbg?.frames()?.map { f ->
        val loc = f.location as? DebugLocation.Native
        mapOf("index" to f.index, "description" to f.description, "pc" to loc?.pc, "file_offset" to loc?.fileOffset)
    } ?: emptyList()

    override fun registers(): Map<String, Long> = dbg?.variables(0)?.associate { it.name to (it.editValue?.toLongOrNull() ?: 0L) } ?: emptyMap()
    override fun setRegister(name: String, value: Long): Boolean = dbg?.setRegister(name, value) ?: false
    override fun writeMemory(address: Long, bytes: ByteArray): Boolean = dbg?.writeMemory(address, bytes) ?: false
    override fun patch(fileOffset: Long, asm: String): Boolean = dbg?.patchNative(fileOffset, asm) ?: false
    override fun onSyscall(cb: (io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Boolean): Boolean {
        dbg?.onSyscall(cb) ?: return false
        return true
    }
    override fun clearSyscall() { dbg?.clearSyscall() }
    override fun hook(address: Long, onEnter: ((io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Unit)?, onLeave: ((io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Unit)?): Int = dbg?.hook(address, onEnter, onLeave) ?: 0
    override fun replace(address: Long, cb: (io.github.nitanmarcel.jdex.disasm.NativeHookContext) -> Long?): Int = dbg?.replace(address, cb) ?: 0
    override fun unhook(id: Int): Boolean = dbg?.unhook(id) ?: false
    override fun memWatch(begin: Long, end: Long, onRead: ((io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit)?, onWrite: ((io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit)?): Int = dbg?.memWatch(begin, end, onRead, onWrite) ?: 0
    override fun trace(begin: Long, end: Long, cb: (io.github.nitanmarcel.jdex.disasm.NativeMemAccess) -> Unit): Int = dbg?.trace(begin, end, cb) ?: 0
    override fun modules(): List<Map<String, Any?>> = dbg?.modules() ?: emptyList()
    override fun symbolAt(address: Long): Map<String, Any?>? = dbg?.symbolAt(address)

    override fun returnValue(): Any? = dbg?.returnValue()
    override fun awaitStop(timeoutMs: Long): Boolean = dbg?.awaitStop(timeoutMs) ?: false
    override fun awaitFinished(timeoutMs: Long): Boolean = dbg?.awaitFinished(timeoutMs) ?: false
}
