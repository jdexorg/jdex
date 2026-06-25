package io.github.nitanmarcel.jdex.debug

data class RegInfo(
    val name: String, val bitsize: Int, val offset: Int,
    val encoding: String = "", val set: String = "", val isSub: Boolean = false,
    val generic: String = "",
)

fun parseRegisterInfo(reply: String): RegInfo? {
    if (reply.isEmpty() || reply[0] == 'E') return null
    val fields = reply.split(';').mapNotNull {
        val k = it.substringBefore(':', "")
        if (k.isEmpty()) null else k to it.substringAfter(':')
    }.toMap()
    val name = fields["name"] ?: return null
    val bitsize = fields["bitsize"]?.toIntOrNull() ?: return null
    val offset = fields["offset"]?.toIntOrNull() ?: -1
    return RegInfo(name, bitsize, offset, fields["encoding"] ?: "", fields["set"] ?: "", "container-regs" in fields, fields["generic"] ?: "")
}

fun assignOffsets(regs: List<RegInfo>): List<RegInfo> {
    var running = 0
    return regs.map { r ->
        val off = if (r.offset >= 0) r.offset else running
        if (!r.isSub) running = off + r.bitsize / 8
        r.copy(offset = off)
    }
}

fun decodeG(regs: List<RegInfo>, gHex: String): List<Pair<String, ULong>> {
    val out = ArrayList<Pair<String, ULong>>(regs.size)
    for (r in regs) {
        val bytes = r.bitsize / 8
        val start = r.offset * 2
        val end = start + bytes * 2
        if (end > gHex.length) { out.add(r.name to 0uL); continue }
        var v = 0uL
        var shift = 0
        var i = start
        while (i < end && shift < 64) {
            val b = gHex.substring(i, i + 2).toULong(16)
            v = v or (b shl shift)
            shift += 8
            i += 2
        }
        out.add(r.name to v)
    }
    return out
}
