package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation
import jadx.api.ICodeInfo
import jadx.api.JavaClass
import jadx.api.metadata.annotations.InsnCodeOffset

object JdecValueInjector {

    fun annotatedSource(cls: JavaClass, info: ICodeInfo, annotations: List<InsnAnnotation>): String {
        val code = info.codeStr
        if (!info.hasMetadata()) return code

        val methods = cls.methods.sortedBy { it.defPos }
        val offsetEntries = info.codeMetadata.asMap.entries.mapNotNull { (pos, ann) ->
            (ann as? InsnCodeOffset)?.let { pos to it.offset }
        }

        val notesByLine = HashMap<Int, MutableList<String>>()
        for (a in annotations) {
            val shortId = a.descriptor.substringAfter("->")
            val mi = methods.indexOfFirst { runCatching { it.methodNode.methodInfo.shortId }.getOrNull() == shortId }
            if (mi < 0) continue
            val start = methods[mi].defPos
            val end = methods.getOrNull(mi + 1)?.defPos ?: code.length
            val inRegion = offsetEntries.filter { it.first in start until end }.sortedBy { it.second }
            val pos = (inRegion.firstOrNull { it.second >= a.offset } ?: inRegion.lastOrNull())?.first ?: continue
            val line = code.substring(0, pos).count { it == '\n' }
            notesByLine.getOrPut(line) { mutableListOf() }.add(a.text)
        }

        if (notesByLine.isEmpty()) return code
        val lines = code.split("\n").toMutableList()
        for ((line, notes) in notesByLine) if (line in lines.indices) lines[line] = lines[line] + "  // jdex: " + notes.distinct().joinToString(", ")
        return lines.joinToString("\n")
    }
}
