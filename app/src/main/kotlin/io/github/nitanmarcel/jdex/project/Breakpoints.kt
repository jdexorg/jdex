package io.github.nitanmarcel.jdex.project

data class StoredBreakpoint(val descriptor: String, val dexPc: Int)

interface BreakpointStore {
    fun breakpoints(): List<StoredBreakpoint>
    fun addBreakpoint(descriptor: String, dexPc: Int)
    fun removeBreakpoint(descriptor: String, dexPc: Int)
}

object NoBreakpoints : BreakpointStore {
    override fun breakpoints(): List<StoredBreakpoint> = emptyList()
    override fun addBreakpoint(descriptor: String, dexPc: Int) {}
    override fun removeBreakpoint(descriptor: String, dexPc: Int) {}
}
