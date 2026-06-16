package io.github.nitanmarcel.jdex.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectTest {

    @Test
    fun stayInMemoryUntilSavedThenPersist(@TempDir dir: File) {
        val apk = File(dir, "sample.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(dir, "sample.jdexproj")

        Project.forInput(apk).use { project ->
            assertTrue(project.isInMemory())
            assertNull(project.file)
            project.setRename("k", "v")
            assertFalse(target.exists())

            project.saveAs(target)
            assertFalse(project.isInMemory())
            assertEquals(target.absolutePath, project.file?.absolutePath)
        }
        assertTrue(target.exists())

        Project.open(target).use {
            assertEquals(apk.absolutePath, it.input()?.absolutePath)
            assertEquals("v", it.renames()["k"])
        }
    }
}
