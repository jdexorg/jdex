package io.github.nitanmarcel.jdex.disasm

object JniDescriptor {
    fun skipType(s: String, start: Int, end: Int = s.length, allowVoid: Boolean = false): Int? {
        var j = start
        while (j < end && s[j] == '[') j++
        if (j >= end) return null
        val arr = j > start
        return when (s[j]) {
            'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> j + 1
            'V' -> if (allowVoid && !arr) j + 1 else null
            'L' -> s.indexOf(';', j + 1).let { semi -> if (semi in (j + 2) until end) semi + 1 else null }
            else -> null
        }
    }

    fun isSignature(s: String): Boolean {
        if (!s.startsWith("(")) return false
        val close = s.indexOf(')')
        if (close < 1) return false
        var i = 1
        while (i < close) i = skipType(s, i, close, false) ?: return false
        return skipType(s, close + 1, s.length, true) == s.length
    }

    fun paramKinds(sig: String): List<Char>? {
        val open = sig.indexOf('('); if (open < 0) return null
        val close = sig.indexOf(')', open); val end = if (close < 0) sig.length else close
        val out = ArrayList<Char>()
        var i = open + 1
        while (i < end) {
            val c = sig[i]
            val next = skipType(sig, i, end, false) ?: break
            out.add(if (c == 'L' || c == '[') 'L' else c)
            i = next
        }
        return out
    }
}
