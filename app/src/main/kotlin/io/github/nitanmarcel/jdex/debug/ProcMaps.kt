package io.github.nitanmarcel.jdex.debug

data class MappedModule(val path: String, val base: Long, val end: Long)

fun parseMaps(text: String): List<MappedModule> {
    data class Seg(val start: Long, val end: Long, val off: Long)
    val byPath = LinkedHashMap<String, MutableList<Seg>>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 6) continue
        val path = parts.subList(5, parts.size).joinToString(" ").removeSuffix(" (deleted)")
        if (!path.endsWith(".so")) continue
        val range = parts[0].split('-')
        if (range.size != 2) continue
        val start = range[0].toLongOrNull(16) ?: continue
        val end = range[1].toLongOrNull(16) ?: continue
        val off = parts[2].toLongOrNull(16) ?: continue
        byPath.getOrPut(path) { ArrayList() }.add(Seg(start, end, off))
    }
    return byPath.map { (path, segs) ->
        val first = segs.minByOrNull { it.start }!!
        MappedModule(path, first.start - first.off, segs.maxOf { it.end })
    }
}

class ModuleResolver(private val modules: List<MappedModule>) {
    private fun basename(p: String) = p.substringAfterLast('/')

    fun all(): List<MappedModule> = modules

    fun resolve(pc: Long): Pair<String, Long>? {
        val m = modules.firstOrNull { pc >= it.base && pc < it.end } ?: return null
        return basename(m.path) to (pc - m.base)
    }

    fun runtimeAddr(basename: String, vaddr: Long): Long? {
        val m = modules.firstOrNull { basename(it.path) == basename } ?: return null
        return m.base + vaddr
    }
}
