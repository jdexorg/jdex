package io.github.nitanmarcel.jdex.debug

sealed interface DebugState {
    data object Detached : DebugState
    data object Running : DebugState
    data class Stopped(val location: DebugLocation) : DebugState
}

sealed interface DebugLocation {
    data class Dex(val methodDescriptor: String, val dexPc: Int) : DebugLocation
    data class Native(val nativeId: String?, val fileOffset: Long, val pc: Long) : DebugLocation
}

sealed interface Breakpoint {
    val enabled: Boolean

    data class Dex(val methodDescriptor: String, val dexPc: Int, override val enabled: Boolean = true) : Breakpoint
    data class Native(val nativeId: String, val fileOffset: Long, override val enabled: Boolean = true) : Breakpoint
}

class Frame(val index: Int, val description: String, val location: DebugLocation)

class ThreadInfo(val id: Long, val name: String, val state: String, val current: Boolean)

class RegisterMeta(val registerCount: Int, val paramStart: Int, val isStatic: Boolean)

class LoadedModule(val name: String, val path: String, val base: Long, val size: Long)

class DebugVar(
    val name: String,
    val type: String,
    val value: String,
    val available: Boolean = true,
    val ref: Long = 0L,
    val editKey: String? = null,
    val editValue: String? = null,
    val id: Long = 0L,
)

interface DebugSession {
    val device: DebugDevice
    val pid: Int
    val state: DebugState

    fun resume()
    fun pause()
    fun stepInto()
    fun stepOver()
    fun stepOut()
    fun detach()

    fun addBreakpoint(bp: Breakpoint)
    fun removeBreakpoint(bp: Breakpoint)
    fun runToCursor(descriptor: String, dexPc: Int) {}
    fun runToCursorNative(nativeId: String, fileOffset: Long) {}
    fun setExceptionBreak(caught: Boolean, uncaught: Boolean) {}

    fun threads(): List<ThreadInfo>
    fun currentThreadId(): Long
    fun frames(threadId: Long): List<Frame>
    fun variables(threadId: Long, frameIndex: Int): List<DebugVar>
    fun children(ref: Long): List<DebugVar>

    fun setValue(editKey: String, text: String): Boolean = false

    fun frames(): List<Frame> = frames(currentThreadId())
    fun variables(frameIndex: Int): List<DebugVar> = variables(currentThreadId(), frameIndex)

    fun readMemory(address: Long, length: Int): ByteArray?
    fun writeMemory(address: Long, bytes: ByteArray): Boolean = false
    fun runtimeAddr(nativeId: String, vaddr: Long): Long? = null

    fun modules(): List<LoadedModule> = emptyList()

    fun inlineValues(): Map<String, String> = emptyMap()

    fun looksLikePointer(address: Long): Boolean = false

    fun architecturalPc(): Long? = null

    fun onStateChange(listener: (DebugState) -> Unit)
}
