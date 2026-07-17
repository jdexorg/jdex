package io.github.nitanmarcel.jdex.debug

interface DebuggerBase {
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

    fun threads(): List<ThreadInfo>
    fun currentThreadId(): Long
    fun frames(threadId: Long): List<Frame>
    fun variables(threadId: Long, frameIndex: Int): List<DebugVar>
    fun children(ref: Long): List<DebugVar>

    fun setValue(editKey: String, text: String): Boolean = false

    fun frames(): List<Frame> = frames(currentThreadId())
    fun variables(frameIndex: Int): List<DebugVar> = variables(currentThreadId(), frameIndex)

    fun onStateChange(listener: (DebugState) -> Unit)
}
