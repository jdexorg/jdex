package io.github.nitanmarcel.jdex.disasm

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.Module
import com.github.unidbg.arm.backend.Backend
import com.github.unidbg.arm.backend.BlockHook
import com.github.unidbg.arm.backend.CodeHook
import com.github.unidbg.arm.backend.ReadHook
import com.github.unidbg.arm.backend.Unicorn2Factory
import com.github.unidbg.arm.backend.UnHook
import com.github.unidbg.arm.backend.WriteHook
import com.github.unidbg.arm.context.Arm64RegisterContext
import com.github.unidbg.arm.context.RegisterContext
import com.github.unidbg.linux.android.AndroidResolver
import com.github.unidbg.linux.android.dvm.AbstractJni
import com.github.unidbg.linux.android.dvm.BaseVM
import com.github.unidbg.linux.android.dvm.DvmClass
import com.github.unidbg.linux.android.dvm.DvmMethod
import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.StringObject
import com.github.unidbg.linux.android.dvm.VarArg
import com.github.unidbg.linux.android.dvm.array.ByteArray as DvmByteArray
import java.io.File

fun interface JavaBridge {
    fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any?
}

class NativeEmulator(
    soBytes: ByteArray,
    private val is64: Boolean,
    sdk: Int = 23,
    private val bridge: JavaBridge? = null,
    timeoutMs: Long = 5_000,
) : AutoCloseable {

    private val soFile = File.createTempFile("jdex-emu", ".so").apply { deleteOnExit(); writeBytes(soBytes) }

    private val emulator: AndroidEmulator = run {
        val factories = listOf(Unicorn2Factory(true))
        (if (is64) JdexAndroidARM64Emulator(null, factories) else JdexAndroidARM32Emulator(null, factories))
            .apply { setTimeout(timeoutMs * 1000) }
    }

    private val vm = run {
        emulator.memory.setLibraryResolver(AndroidResolver(sdk))
        emulator.createDalvikVM(null as File?).also {
            it.setVerbose(false)
            if (bridge != null) it.setJni(jniHandler())
        }
    }

    private val module: Module = vm.loadLibrary(soFile, false).also { it.callJNI_OnLoad(emulator) }.module

    fun symbolAddress(name: String): Long? =
        runCatching { module.findSymbolByName(name, true)?.address?.takeIf { it != 0L } }.getOrNull()

    val moduleBase: Long get() = module.base

    fun malloc(size: Int): Long =
        runCatching { emulator.memory.malloc(size, false).pointer.peer }.getOrDefault(0L)

    internal fun readRegister(name: String): Long? {
        val id = regId(name) ?: return null
        return runCatching { emulator.backend.reg_read(id).toLong() }.getOrNull()
    }

    internal fun installCodeHook(onStep: (address: Long, size: Int) -> Unit) {
        emulator.backend.hook_add_new(object : CodeHook {
            override fun hook(backend: Backend, address: Long, size: Int, user: Any?) = onStep(address, size)
            override fun onAttach(unHook: UnHook) {}
            override fun detach() {}
        }, 1L, 0L, null)
    }

    internal fun installReadHook(begin: Long, end: Long, cb: (NativeMemAccess) -> Unit): () -> Unit {
        var unhook: UnHook? = null
        emulator.backend.hook_add_new(object : ReadHook {
            override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
                runCatching { cb(NativeMemAccess(this@NativeEmulator, address, size, 0L, false)) }
            }
            override fun onAttach(u: UnHook) { unhook = u }
            override fun detach() {}
        }, begin, end, null)
        return { runCatching { unhook?.unhook() } }
    }

    internal fun installWriteHook(begin: Long, end: Long, cb: (NativeMemAccess) -> Unit): () -> Unit {
        var unhook: UnHook? = null
        emulator.backend.hook_add_new(object : WriteHook {
            override fun hook(backend: Backend, address: Long, size: Int, value: Long, user: Any?) {
                runCatching { cb(NativeMemAccess(this@NativeEmulator, address, size, value, true)) }
            }
            override fun onAttach(u: UnHook) { unhook = u }
            override fun detach() {}
        }, begin, end, null)
        return { runCatching { unhook?.unhook() } }
    }

    internal fun installBlockHook(begin: Long, end: Long, cb: (NativeMemAccess) -> Unit): () -> Unit {
        var unhook: UnHook? = null
        emulator.backend.hook_add_new(object : BlockHook {
            override fun hookBlock(backend: Backend, address: Long, size: Int, user: Any?) {
                runCatching { cb(NativeMemAccess(this@NativeEmulator, address, size, 0L, false)) }
            }
            override fun onAttach(u: UnHook) { unhook = u }
            override fun detach() {}
        }, begin, end, null)
        return { runCatching { unhook?.unhook() } }
    }

    internal fun loadedModules(): List<Map<String, Any?>> =
        runCatching { emulator.memory.loadedModules.map { mapOf("name" to it.name, "base" to it.base, "size" to it.size) } }
            .getOrDefault(emptyList())

    internal fun symbolAt(address: Long): Map<String, Any?>? = runCatching {
        val m = emulator.memory.findModuleByAddress(address) ?: return null
        val s = m.findClosestSymbolByAddress(address, false) ?: return null
        mapOf("name" to s.name, "module" to (s.moduleName ?: m.name), "offset" to (address - s.address))
    }.getOrNull()

    internal fun hookContext() = NativeHookContext(this, is64)

    internal fun setSyscallInterceptor(cb: SyscallInterceptor?) {
        (emulator.syscallHandler as? JdexSyscallHandler64)?.interceptor = cb
        (emulator.syscallHandler as? JdexSyscallHandler32)?.interceptor = cb
    }

    internal fun syscallInterceptor(cb: (NativeHookContext) -> Boolean) {
        val ctx = hookContext()
        setSyscallInterceptor(SyscallInterceptor { runCatching { cb(ctx) }.getOrDefault(false) })
    }

    internal fun currentSp(): Long = runCatching { emulator.getContext<RegisterContext>().stackPointer?.peer ?: 0L }.getOrDefault(0L)

    private fun regId(name: String): Int? = if (is64) when {
        name == "sp" -> 4; name == "pc" -> 260; name == "lr" -> 2; name == "fp" -> 1
        name.startsWith("x") -> name.drop(1).toIntOrNull()?.let {
            when { it in 0..28 -> 199 + it; it == 29 -> 1; it == 30 -> 2; else -> null }
        }
        else -> null
    } else when {
        name == "sp" -> 12; name == "pc" -> 11; name == "lr" -> 10
        name.startsWith("r") -> name.drop(1).toIntOrNull()?.takeIf { it in 0..12 }?.let { 66 + it }
        else -> null
    }

    internal fun writeRegister(name: String, value: Long): Boolean {
        val id = regId(name) ?: return false
        return runCatching { emulator.backend.reg_write(id, value); true }.getOrDefault(false)
    }

    internal fun writeMemory(address: Long, bytes: ByteArray): Boolean =
        runCatching { emulator.backend.mem_write(address, bytes); true }.getOrDefault(false)

    internal fun readMemory(address: Long, size: Int): ByteArray? =
        runCatching { emulator.backend.mem_read(address, size.toLong()) }.getOrNull()

    internal fun stopEmulation() { runCatching { emulator.backend.emu_stop() } }

    internal fun snapshotRegisters(pc: Long): Map<String, Long> {
        val ctx = emulator.getContext<RegisterContext>()
        val out = LinkedHashMap<String, Long>()
        out["pc"] = pc
        runCatching { out["sp"] = ctx.stackPointer?.peer ?: 0L }
        runCatching { out["lr"] = ctx.lr }
        if (is64 && ctx is Arm64RegisterContext) {
            for (i in 0..28) runCatching { out["x$i"] = ctx.getXLong(i) }
            runCatching { out["fp"] = ctx.fp }
        } else {
            for (i in 0..3) runCatching { out["r$i"] = ctx.getLongArg(i) }
        }
        return out
    }

    constructor(elf: ElfFile, soBytes: ByteArray, sdk: Int = 23, bridge: JavaBridge? = null, timeoutMs: Long = 5_000) :
        this(soBytes, elf.is64, sdk, bridge, timeoutMs)

    fun callStaticString(className: String, methodSig: String, vararg args: Any?): String? =
        callString(className, methodSig, null, args.toList())

    fun callString(className: String, methodSig: String, receiver: Any?, args: List<Any?>): String? {
        val cls = vm.resolveClass(className.replace('.', '/'))
        val marshalled = args.map { marshal(it) }.toTypedArray()
        val result = if (receiver != null)
            cls.newObject(receiver).callJniMethodObject<DvmObject<*>>(emulator, methodSig, *marshalled)
        else
            cls.callStaticJniMethodObject<DvmObject<*>>(emulator, methodSig, *marshalled)
        return result?.value as? String
    }

    fun callFunction(offset: Long, vararg args: Any?): Long =
        module.callFunction(emulator, offset, *args).toLong()

    fun callAddress(address: Long, vararg args: Any?): Long =
        Module.emulateFunction(emulator, address, *args).toLong()

    fun emulate(begin: Long, until: Long): Long {
        emulator.backend.emu_start(begin, until, 0L, 0L)
        return runCatching { emulator.backend.reg_read(if (is64) 199 else 66).toLong() }.getOrDefault(0L)
    }

    private fun marshal(a: Any?): Any? = when (a) {
        is String -> StringObject(vm, a)
        is ByteArray -> DvmByteArray(vm, a)
        is io.github.nitanmarcel.jdex.exec.runtime.DvmObject -> vm.resolveClass(a.type.removePrefix("L").removeSuffix(";")).newObject(a)
        else -> a
    }

    private fun jniHandler(): AbstractJni = object : AbstractJni() {
        override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>? =
            dispatch(dvmClass, dvmMethod, varArg, null) ?: super.callStaticObjectMethod(vm, dvmClass, dvmMethod, varArg)

        override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>? =
            dispatch(dvmObject.objectType, dvmMethod, varArg, dvmObject.value) ?: super.callObjectMethod(vm, dvmObject, dvmMethod, varArg)

        override fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> =
            construct(dvmClass, dvmMethod, varArg) ?: super.newObject(vm, dvmClass, dvmMethod, varArg)
    }

    private fun construct(dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>? {
        val b = bridge ?: return null
        val sig = dvmMethod.args
        val obj = runCatching { b.call(dvmClass.className, "<init>", sig, extractArgs(sig, varArg), null) }.getOrNull() ?: return null
        return dvmClass.newObject(obj)
    }

    private fun dispatch(dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg, receiver: Any?): DvmObject<*>? {
        val b = bridge ?: return null
        val sig = dvmMethod.args
        val result = runCatching { b.call(dvmClass.className, dvmMethod.methodName, sig, extractArgs(sig, varArg), receiver) }.getOrNull() ?: return null
        return when (result) {
            is String -> StringObject(vm, result)
            is ByteArray -> DvmByteArray(vm, result)
            is DvmObject<*> -> result
            else -> null
        }
    }

    private fun extractArgs(signature: String, varArg: VarArg): List<Any?> {
        val params = signature.substringAfter('(').substringBefore(')')
        val out = ArrayList<Any?>()
        var i = 0
        var idx = 0
        while (i < params.length) {
            when (params[i]) {
                'L' -> { out.add((varArg.getObjectArg<DvmObject<*>>(idx))?.value); i = params.indexOf(';', i) + 1 }
                '[' -> { out.add((varArg.getObjectArg<DvmObject<*>>(idx))?.value); i++; if (i < params.length && params[i] == 'L') i = params.indexOf(';', i) + 1 else i++ }
                'J' -> { out.add(varArg.getLongArg(idx)); i++ }
                else -> { out.add(varArg.getIntArg(idx)); i++ }
            }
            idx++
        }
        return out
    }

    override fun close() {
        runCatching { emulator.close() }
        soFile.delete()
    }
}
