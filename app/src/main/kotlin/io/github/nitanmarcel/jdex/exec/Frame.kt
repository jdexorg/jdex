package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.WideHigh

class Frame(size: Int) {
    val regs: Array<Any?> = arrayOfNulls(size)
    var result: Any? = null
    var resultType: String? = null
    var pendingException: Any? = null

    fun get(r: Int): Any? = regs[r]
    fun set(r: Int, v: Any?) { regs[r] = v }
    fun setWide(r: Int, v: Any?) {
        regs[r] = v
        if (r + 1 < regs.size) regs[r + 1] = WideHigh
    }

    fun replace(old: Any?, new: Any?) {
        if (old == null) return
        for (i in regs.indices) if (regs[i] === old) regs[i] = new
    }
}
