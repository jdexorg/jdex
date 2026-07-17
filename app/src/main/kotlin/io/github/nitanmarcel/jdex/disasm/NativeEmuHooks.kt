package io.github.nitanmarcel.jdex.disasm

import com.github.unidbg.Emulator
import com.github.unidbg.arm.backend.BackendFactory
import com.github.unidbg.file.linux.AndroidFileIO
import com.github.unidbg.linux.ARM32SyscallHandler
import com.github.unidbg.linux.ARM64SyscallHandler
import com.github.unidbg.linux.android.AndroidARM64Emulator
import com.github.unidbg.linux.android.AndroidARMEmulator
import com.github.unidbg.memory.SvcMemory
import com.github.unidbg.unix.UnixSyscallHandler

fun interface SyscallInterceptor {
    fun handle(nr: Int): Boolean
}

class JdexSyscallHandler64(svcMemory: SvcMemory) : ARM64SyscallHandler(svcMemory) {
    @Volatile
    var interceptor: SyscallInterceptor? = null

    public override fun handleSyscall(emulator: Emulator<*>, NR: Int): Boolean =
        interceptor?.handle(NR) ?: super.handleSyscall(emulator, NR)
}

class JdexSyscallHandler32(svcMemory: SvcMemory) : ARM32SyscallHandler(svcMemory) {
    @Volatile
    var interceptor: SyscallInterceptor? = null

    public override fun handleSyscall(emulator: Emulator<*>, NR: Int): Boolean =
        interceptor?.handle(NR) ?: super.handleSyscall(emulator, NR)
}

class JdexAndroidARM64Emulator(processName: String?, backendFactories: Collection<BackendFactory>) :
    AndroidARM64Emulator(processName, null, backendFactories) {
    override fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<AndroidFileIO> =
        JdexSyscallHandler64(svcMemory)
}

class JdexAndroidARM32Emulator(processName: String?, backendFactories: Collection<BackendFactory>) :
    AndroidARMEmulator(processName, null, backendFactories) {
    override fun createSyscallHandler(svcMemory: SvcMemory): UnixSyscallHandler<AndroidFileIO> =
        JdexSyscallHandler32(svcMemory)
}

class NativeHookContext(private val emu: NativeEmulator, private val is64: Boolean) {
    private fun scReg() = if (is64) "x8" else "r7"
    private fun retReg() = if (is64) "x0" else "r0"
    private fun argReg(i: Int) = if (is64) "x$i" else "r$i"

    val number: Long get() = emu.readRegister(scReg()) ?: 0L
    fun reg(name: String): Long = emu.readRegister(name) ?: 0L
    fun setReg(name: String, value: Long): Boolean = emu.writeRegister(name, value)
    fun arg(index: Int): Long = emu.readRegister(argReg(index)) ?: 0L
    fun setArg(index: Int, value: Long): Boolean = emu.writeRegister(argReg(index), value)
    fun ret(): Long = emu.readRegister(retReg()) ?: 0L
    fun setRet(value: Long): Boolean = emu.writeRegister(retReg(), value)
    fun readMem(address: Long, size: Int): ByteArray? = emu.readMemory(address, size)
    fun writeMem(address: Long, bytes: ByteArray): Boolean = emu.writeMemory(address, bytes)
}

class NativeMemAccess(
    private val emu: NativeEmulator,
    private val addr: Long,
    private val sz: Int,
    private val v: Long,
    private val write: Boolean,
) {
    fun address(): Long = addr
    fun size(): Int = sz
    fun value(): Long = v
    fun isWrite(): Boolean = write
    fun reg(name: String): Long = emu.readRegister(name) ?: 0L
    fun setReg(name: String, value: Long): Boolean = emu.writeRegister(name, value)
    fun readMem(address: Long, size: Int): ByteArray? = emu.readMemory(address, size)
    fun writeMem(address: Long, bytes: ByteArray): Boolean = emu.writeMemory(address, bytes)
}
