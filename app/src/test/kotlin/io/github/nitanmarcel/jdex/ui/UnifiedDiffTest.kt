package io.github.nitanmarcel.jdex.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnifiedDiffTest {

    @Test
    fun identicalHasOnlyContextLinesAndNoChanges() {
        val d = UnifiedDiff.of("a\nb\nc", "a\nb\nc")
        assertEquals(listOf("  a", "  b", "  c"), d.text.split("\n"))
        assertEquals(emptySet<Int>(), d.addedLines)
        assertEquals(emptySet<Int>(), d.removedLines)
    }

    @Test
    fun pureInsertionMarksAddedLine() {
        val d = UnifiedDiff.of("a\nb", "a\nx\nb")
        assertEquals(listOf("  a", "+ x", "  b"), d.text.split("\n"))
        assertEquals(setOf(1), d.addedLines)
        assertEquals(emptySet<Int>(), d.removedLines)
    }

    @Test
    fun pureDeletionMarksRemovedLine() {
        val d = UnifiedDiff.of("a\nx\nb", "a\nb")
        assertEquals(listOf("  a", "- x", "  b"), d.text.split("\n"))
        assertEquals(setOf(1), d.removedLines)
        assertEquals(emptySet<Int>(), d.addedLines)
    }

    @Test
    fun changeEmitsRemovedThenAdded() {
        val d = UnifiedDiff.of("a\nb\nc", "a\nB\nc")
        assertEquals(listOf("  a", "- b", "+ B", "  c"), d.text.split("\n"))
        assertEquals(setOf(1), d.removedLines)
        assertEquals(setOf(2), d.addedLines)
    }
}
