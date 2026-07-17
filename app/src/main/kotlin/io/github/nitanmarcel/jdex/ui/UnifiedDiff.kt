package io.github.nitanmarcel.jdex.ui

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

data class DiffText(val text: String, val addedLines: Set<Int>, val removedLines: Set<Int>)

object UnifiedDiff {
    fun of(old: String, new: String): DiffText {
        val oldLines = old.split("\n")
        val deltas = DiffUtils.diff(oldLines, new.split("\n")).deltas.sortedBy { it.source.position }
        val out = ArrayList<String>()
        val added = HashSet<Int>()
        val removed = HashSet<Int>()
        var oldIdx = 0
        for (delta in deltas) {
            while (oldIdx < delta.source.position) { out.add("  " + oldLines[oldIdx]); oldIdx++ }
            if (delta.type == DeltaType.DELETE || delta.type == DeltaType.CHANGE) {
                for (l in delta.source.lines) { removed.add(out.size); out.add("- $l") }
            }
            if (delta.type == DeltaType.INSERT || delta.type == DeltaType.CHANGE) {
                for (l in delta.target.lines) { added.add(out.size); out.add("+ $l") }
            }
            oldIdx = delta.source.position + delta.source.size()
        }
        while (oldIdx < oldLines.size) { out.add("  " + oldLines[oldIdx]); oldIdx++ }
        return DiffText(out.joinToString("\n"), added, removed)
    }
}
