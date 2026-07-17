package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.debug.Breakpoint
import io.github.nitanmarcel.jdex.debug.DebugLocation
import io.github.nitanmarcel.jdex.debug.DebugState
import io.github.nitanmarcel.jdex.debug.DebugVar
import io.github.nitanmarcel.jdex.debug.EmulatorDebugger
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal

class EmulatorDebuggerControl(
    private val source: () -> MethodSource?,
    private val nativeBridge: () -> io.github.nitanmarcel.jdex.exec.NativeBridge? = { null },
    private val world: () -> io.github.nitanmarcel.jdex.exec.EmuWorld = { io.github.nitanmarcel.jdex.exec.EmuWorld() },
) : EmuControl {

    private var dbg: EmulatorDebugger? = null
    private var dbgSource: MethodSource? = null
    private val theWorld: io.github.nitanmarcel.jdex.exec.EmuWorld by lazy { world() }

    private fun debugger(): EmulatorDebugger? {
        val cur = source()
        if (dbg != null && cur !== dbgSource) reset()
        if (dbg == null) cur?.let { dbg = EmulatorDebugger(it, nativeBridge = nativeBridge(), world = theWorld); dbgSource = it }
        return dbg
    }

    override fun newObject(classDesc: String, ctorSig: String?, args: List<Any?>): EmuObject? =
        source()?.let { constructEmuObject(it, theWorld, classDesc, ctorSig, args) }

    fun session(): EmulatorDebugger? = debugger()

    fun reset() { dbg?.detach(); dbg = null; dbgSource = null }

    override fun run(descriptor: String, args: List<Any?>, pauseAtEntry: Boolean): Boolean {
        val d = debugger() ?: return false
        d.runMethod(descriptor, args.map(::emuArg), pauseAtEntry = pauseAtEntry)
        return true
    }

    override fun detach() { dbg?.detach() }
    override fun resume() { dbg?.resume() }
    override fun pause() { dbg?.pause() }
    override fun stepInto() { dbg?.stepInto() }
    override fun stepOver() { dbg?.stepOver() }
    override fun stepOut() { dbg?.stepOut() }

    override fun setBreakpoint(descriptor: String, dexPc: Int) { debugger()?.addBreakpoint(Breakpoint.Dex(descriptor, dexPc)) }
    override fun clearBreakpoint(descriptor: String, dexPc: Int) { debugger()?.removeBreakpoint(Breakpoint.Dex(descriptor, dexPc)) }
    override fun runToCursor(descriptor: String, dexPc: Int) { dbg?.runToCursor(descriptor, dexPc) }

    override fun state(): String = when (dbg?.state) {
        is DebugState.Running -> "running"
        is DebugState.Stopped -> "stopped"
        else -> "detached"
    }

    override fun frames(): List<Map<String, Any?>> = dbg?.frames()?.map { f ->
        val loc = f.location as? DebugLocation.Dex
        mapOf("index" to f.index, "description" to f.description, "descriptor" to loc?.methodDescriptor, "dex_pc" to loc?.dexPc)
    } ?: emptyList()

    override fun variables(frameIndex: Int): List<Map<String, Any?>> = dbg?.variables(frameIndex)?.map(::varMap) ?: emptyList()

    override fun children(ref: Long): List<Map<String, Any?>> = dbg?.children(ref)?.map(::varMap) ?: emptyList()

    override fun setValue(editKey: String, text: String): Boolean = dbg?.setValue(editKey, text) ?: false

    override fun setRegister(frameIndex: Int, reg: Int, value: Any?): Boolean = dbg?.setRegister(frameIndex, reg, emuArg(value)) ?: false

    override fun returnValue(): Any? = pyFriendly(dbg?.returnValue())

    override fun resolve(descriptor: String, args: List<Any?>?): Map<String, Any?> {
        val r = debugger()?.resolve(descriptor, args?.map(::emuArg)) ?: return emptyMap()
        return mapOf("return" to pyFriendly(r.returnValue), "unknown" to (r.returnValue == null || r.returnValue is UnknownVal))
    }

    override fun registerStub(classDesc: String, name: String, handler: (Any?, List<Any?>) -> Any?) {
        debugger()?.registerStub(classDesc, name) { recv, args -> handler(recv, args) }
    }

    override fun registerField(classDesc: String, name: String, value: Any?) { debugger()?.registerField(classDesc, name, emuArg(value)) }

    override fun awaitStop(timeoutMs: Long): Boolean = dbg?.awaitStop(timeoutMs) ?: false
    override fun awaitFinished(timeoutMs: Long): Boolean = dbg?.awaitFinished(timeoutMs) ?: false

    override fun hook(descriptor: String, hook: io.github.nitanmarcel.jdex.exec.Interceptor): Int = theWorld.hooks.add(descriptor, hook)
    override fun unhook(id: Int): Boolean = theWorld.hooks.remove(id)
    override fun installedHooks(): List<Map<String, Any?>> = theWorld.hooks.list().map { (id, d) -> mapOf("id" to id, "descriptor" to d) }
    override fun clearHooks() = theWorld.hooks.clear()

    private fun varMap(v: DebugVar): Map<String, Any?> =
        mapOf("name" to v.name, "type" to v.type, "value" to v.value, "ref" to v.ref, "edit_key" to v.editKey, "available" to v.available)
}
