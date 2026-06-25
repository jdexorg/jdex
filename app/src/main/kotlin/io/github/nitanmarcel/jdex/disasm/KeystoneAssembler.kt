package io.github.nitanmarcel.jdex.disasm

import com.sun.jna.NativeLong
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

object KeystoneAssembler {
    private const val MODE_BIG_ENDIAN = 1 shl 30
    private const val OPT_SYNTAX = 1
    private const val OPT_SYNTAX_INTEL = 1 shl 1

    private fun archMode(arch: ElfArch, thumb: Boolean): Pair<Int, Int>? = when (arch) {
        ElfArch.ARM -> 1 to (if (thumb) 1 shl 4 else 1)
        ElfArch.ARM64 -> 2 to 0
        ElfArch.X86 -> 4 to (1 shl 2)
        ElfArch.X86_64 -> 4 to (1 shl 3)
        ElfArch.MIPS -> 3 to (1 shl 2)
        ElfArch.MIPS64 -> 3 to (1 shl 3)
        ElfArch.UNKNOWN -> null
    }

    fun supports(arch: ElfArch) = archMode(arch, false) != null

    fun available(): Boolean = runCatching { KeystoneLib.INSTANCE }.isSuccess

    fun assemble(asm: String, address: Long, arch: ElfArch, thumb: Boolean = false, littleEndian: Boolean = true): Result<ByteArray> {
        val (ksArch, baseMode) = archMode(arch, thumb) ?: return Result.failure(IllegalArgumentException("unsupported arch $arch"))
        val mode = if (littleEndian) baseMode else baseMode or MODE_BIG_ENDIAN
        val lib = KeystoneLib.INSTANCE
        val ksRef = PointerByReference()
        if (lib.ks_open(ksArch, mode, ksRef) != 0) return Result.failure(IllegalStateException("ks_open failed"))
        val ks = ksRef.value
        try {
            if (arch == ElfArch.X86 || arch == ElfArch.X86_64) lib.ks_option(ks, OPT_SYNTAX, NativeLong(OPT_SYNTAX_INTEL.toLong()))
            val enc = PointerByReference()
            val size = NativeLongByReference()
            val count = NativeLongByReference()
            if (lib.ks_asm(ks, asm, address, enc, size, count) != 0) {
                return Result.failure(IllegalArgumentException(lib.ks_strerror(lib.ks_errno(ks)) ?: "assemble failed"))
            }
            val n = size.value.toInt()
            val bytes = if (n > 0 && enc.value != null) enc.value.getByteArray(0, n) else ByteArray(0)
            enc.value?.let { lib.ks_free(it) }
            return Result.success(bytes)
        } finally {
            lib.ks_close(ks)
        }
    }
}
