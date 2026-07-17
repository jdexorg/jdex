package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.NativeBridge
import io.github.nitanmarcel.jdex.exec.NotHandled
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal

class MixedNativeBridge(
    source: MethodSource,
    soBytes: ByteArray,
    is64: Boolean,
    otherSoBytes: List<ByteArray> = emptyList(),
    world: io.github.nitanmarcel.jdex.exec.EmuWorld? = null,
) : NativeBridge, AutoCloseable {

    val controller = NativeEmuController(soBytes, is64, bridge = VmJavaBridge(source, world = world))
    private val others = if (otherSoBytes.isEmpty()) null else DexToNativeBridge(source, otherSoBytes)

    override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
        if (args.any { it is UnknownVal }) return NotHandled
        runCatching { controller.callSync(className, methodName + signature, receiver, args) }
            .getOrNull()?.let { return it }
        return others?.call(className, methodName, signature, args, receiver) ?: NotHandled
    }

    override fun close() { controller.close(); others?.close() }
}
