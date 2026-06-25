package io.github.nitanmarcel.jdex.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BytecodeOffsetsTest {

    private val insnLine = Regex("^\\s*[0-9a-f]+:\\s+(?:[0-9a-f]+\\s+)*([0-9a-f]+):")

    private fun fixture(dir: File): File {
        val bytes = javaClass.getResourceAsStream("/fixtures/jdex-debug-target.apk")!!.readBytes()
        return File(dir, "fixture.apk").apply { writeBytes(bytes) }
    }

    @Test
    fun dexPcAtMatchesRenderedOffsets(@TempDir dir: File) {
        val session = ApkSession.load(fixture(dir), "offsets")
        try {
            val jadx = session.decompiler()
            val resources = runCatching { jadx.root.constValues.resourcesNames }.getOrDefault(emptyMap())
            val chunks = jadx.classesWithInners.sortedBy { it.rawName }.map { cls ->
                val listing = BytecodeWriter.forClassListing(cls, resources)
                LabeledChunk(cls.rawName, listing.text + "\n", listing.offsetLines, listing.dexPcs)
            }
            val source = DiskLineSource.build(chunks.asSequence())
            try {
                var instructions = 0
                for (line in 0 until source.lineCount) {
                    val text = source.lines(line, 1).firstOrNull() ?: ""
                    val m = insnLine.find(text)
                    if (m != null) {
                        assertEquals(m.groupValues[1].toInt(16), source.dexPcAt(line), "instruction line $line: <$text>")
                        instructions++
                    } else {
                        assertNull(source.dexPcAt(line), "non-instruction line $line has a dexPc: <$text>")
                    }
                }
                assertTrue(instructions > 20, "expected instruction lines, got $instructions")
                println("OFFSETS verified $instructions instruction lines across ${source.lineCount} lines")
            } finally {
                source.close()
            }
        } finally {
            session.close()
        }
    }
}
