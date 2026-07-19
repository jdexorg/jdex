package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.debug.Breakpoint
import io.github.nitanmarcel.jdex.debug.DebugLocation
import io.github.nitanmarcel.jdex.debug.DebugState
import io.github.nitanmarcel.jdex.debug.DebugVar
import io.github.nitanmarcel.jdex.debug.DebuggerBase
import io.github.nitanmarcel.jdex.exec.Interceptor

class ActiveEmuControl(
    private val activeEmu: () -> DebuggerBase?,
    private val world: () -> io.github.nitanmarcel.jdex.exec.EmuWorld,
    private val source: () -> io.github.nitanmarcel.jdex.exec.MethodSource?,
) : EmuControl {

    private fun na(what: String): Nothing =
        throw UnsupportedOperationException("$what is not available on jdex.this.emu (the running UI session) — use jdex.emu.$what")

    override fun run(descriptor: String, args: List<Any?>, pauseAtEntry: Boolean): Boolean = na("run")
    override fun resolve(descriptor: String, args: List<Any?>?): Map<String, Any?> = na("resolve")
    override fun registerStub(classDesc: String, name: String, handler: (Any?, List<Any?>) -> Any?) =
        world().android.registerMethod(classDesc, name) { recv, args -> handler(recv, args) }
    override fun registerField(classDesc: String, name: String, value: Any?) =
        world().android.registerField(classDesc, name, emuArg(value))
    override fun setRegister(frameIndex: Int, reg: Int, value: Any?): Boolean = na("set_register")
    override fun runToCursor(descriptor: String, dexPc: Int) = na("run_to_cursor")
    override fun returnValue(): Any? = na("return_value")
    override fun awaitStop(timeoutMs: Long): Boolean = na("await_stop")
    override fun awaitFinished(timeoutMs: Long): Boolean = na("await_finished")

    override fun detach() { activeEmu()?.detach() }
    override fun resume() { activeEmu()?.resume() }
    override fun pause() { activeEmu()?.pause() }
    override fun stepInto() { activeEmu()?.stepInto() }
    override fun stepOver() { activeEmu()?.stepOver() }
    override fun stepOut() { activeEmu()?.stepOut() }
    override fun setBreakpoint(descriptor: String, dexPc: Int) { activeEmu()?.addBreakpoint(Breakpoint.Dex(descriptor, dexPc)) }
    override fun clearBreakpoint(descriptor: String, dexPc: Int) { activeEmu()?.removeBreakpoint(Breakpoint.Dex(descriptor, dexPc)) }

    override fun state(): String = when (activeEmu()?.state) {
        is DebugState.Running -> "running"
        is DebugState.Stopped -> "stopped"
        else -> "detached"
    }

    override fun frames(): List<Map<String, Any?>> = activeEmu()?.frames(1L)?.map { f ->
        val dex = f.location as? DebugLocation.Dex
        val nat = f.location as? DebugLocation.Native
        mapOf("index" to f.index, "description" to f.description, "descriptor" to dex?.methodDescriptor,
            "dex_pc" to dex?.dexPc, "file_offset" to nat?.fileOffset, "pc" to nat?.pc)
    } ?: emptyList()

    override fun variables(frameIndex: Int): List<Map<String, Any?>> = activeEmu()?.variables(1L, frameIndex)?.map(::varMap) ?: emptyList()
    override fun children(ref: Long): List<Map<String, Any?>> = activeEmu()?.children(ref)?.map(::varMap) ?: emptyList()
    override fun setValue(editKey: String, text: String): Boolean = activeEmu()?.setValue(editKey, text) ?: false

    override fun hook(descriptor: String, hook: Interceptor): Int = world().hooks.add(descriptor, hook)
    override fun unhook(id: Int): Boolean = world().hooks.remove(id)
    override fun installedHooks(): List<Map<String, Any?>> = world().hooks.list().map { (id, d) -> mapOf("id" to id, "descriptor" to d) }
    override fun clearHooks() = world().hooks.clear()

    override fun newObject(classDesc: String, ctorSig: String?, args: List<Any?>): EmuObject? =
        source()?.let { constructEmuObject(it, world(), classDesc, ctorSig, args) }

    private fun varMap(v: DebugVar): Map<String, Any?> =
        mapOf("name" to v.name, "type" to v.type, "value" to pyFriendly(v.value), "ref" to v.ref, "edit_key" to v.editKey, "available" to v.available)
}
