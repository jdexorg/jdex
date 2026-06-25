package io.github.nitanmarcel.jdex.debug

object Rendezvous {
    class Result(val rBrk: Long, val rDebug: Long)

    private const val AT_NULL = 0L
    private const val AT_PHDR = 3L
    private const val AT_PHENT = 4L
    private const val AT_PHNUM = 5L
    private const val PT_DYNAMIC = 2
    private const val PT_PHDR = 6
    private const val DT_NULL = 0L
    private const val DT_DEBUG = 21L

    fun resolve(auxv: ByteArray, is64: Boolean, readMem: (Long, Int) -> ByteArray?): Result? {
        val (phdr, phent, phnum) = parseAuxv(auxv, is64) ?: return null
        val ph = readMem(phdr, phent * phnum) ?: return null
        var phdrVaddr: Long? = null
        var dynVaddr: Long? = null
        for (i in 0 until phnum) {
            val o = i * phent
            if (o + (if (is64) 24 else 12) > ph.size) break
            val type = u32(ph, o)
            val pv = if (is64) i64(ph, o + 16) else u32(ph, o + 8).toLong() and 0xFFFFFFFFL
            if (type == PT_PHDR) phdrVaddr = pv
            if (type == PT_DYNAMIC) dynVaddr = pv
        }
        val bias = phdr - (phdrVaddr ?: return null)
        val dynRuntime = bias + (dynVaddr ?: return null)
        val rDebug = findDtDebug(dynRuntime, is64, readMem) ?: return null
        val brkBytes = readMem(rDebug + (if (is64) 16 else 8), if (is64) 8 else 4) ?: return null
        val rBrk = if (is64) i64(brkBytes, 0) else u32(brkBytes, 0).toLong() and 0xFFFFFFFFL
        if (rBrk == 0L) return null
        return Result(rBrk, rDebug)
    }

    fun stateOffset(is64: Boolean): Int = if (is64) 24 else 12

    private fun parseAuxv(auxv: ByteArray, is64: Boolean): Triple<Long, Int, Int>? {
        val step = if (is64) 16 else 8
        var off = 0
        var phdr = 0L
        var phent = 0L
        var phnum = 0L
        while (off + step <= auxv.size) {
            val type = if (is64) i64(auxv, off) else u32(auxv, off).toLong() and 0xFFFFFFFFL
            val v = if (is64) i64(auxv, off + 8) else u32(auxv, off + 4).toLong() and 0xFFFFFFFFL
            if (type == AT_NULL) break
            when (type) {
                AT_PHDR -> phdr = v
                AT_PHENT -> phent = v
                AT_PHNUM -> phnum = v
            }
            off += step
        }
        if (phdr == 0L || phent == 0L || phnum == 0L) return null
        return Triple(phdr, phent.toInt(), phnum.toInt())
    }

    private fun findDtDebug(dynRuntime: Long, is64: Boolean, readMem: (Long, Int) -> ByteArray?): Long? {
        val step = if (is64) 16 else 8
        var off = 0L
        while (off < 8192) {
            val e = readMem(dynRuntime + off, step) ?: return null
            if (e.size < step) return null
            val tag = if (is64) i64(e, 0) else u32(e, 0).toLong() and 0xFFFFFFFFL
            val v = if (is64) i64(e, 8) else u32(e, 4).toLong() and 0xFFFFFFFFL
            if (tag == DT_NULL) return null
            if (tag == DT_DEBUG) return v
            off += step
        }
        return null
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun i64(b: ByteArray, o: Int): Long {
        var r = 0L
        for (k in 0..7) r = r or ((b[o + k].toLong() and 0xff) shl (8 * k))
        return r
    }
}
