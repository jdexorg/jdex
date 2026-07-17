package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.NativeBridge
import io.github.nitanmarcel.jdex.exec.NotHandled
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal

class DexToNativeBridge(source: MethodSource, private val soBytesList: List<ByteArray>) : NativeBridge, AutoCloseable {

    private val javaBridge = VmJavaBridge(source)
    private val emulators = HashMap<Int, NativeEmulator?>()

    override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
        if (args.any { it is UnknownVal }) return NotHandled
        for (i in soBytesList.indices) {
            val emu = emulators.getOrPut(i) { build(soBytesList[i]) } ?: continue
            val r = runCatching { emu.callString(className, methodName + signature, receiver, args) }
            if (r.isSuccess) r.getOrNull()?.let { return it }
        }
        return NotHandled
    }

    private fun build(bytes: ByteArray): NativeEmulator? {
        val elf = ElfFile.parse(bytes) ?: return null
        if (elf.arch != ElfArch.ARM && elf.arch != ElfArch.ARM64) return null
        return runCatching { NativeEmulator(bytes, elf.is64, bridge = javaBridge) }.getOrNull()
    }

    override fun close() = emulators.values.forEach { runCatching { it?.close() } }.let { }
}
