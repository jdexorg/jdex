package io.github.nitanmarcel.jdex.ui

import com.formdev.flatlaf.FlatLightLaf
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class JavaViewDiffTest {

    private fun <T> find(c: Component, cls: Class<T>): T? {
        if (cls.isInstance(c)) return cls.cast(c)
        if (c is Container) for (ch in c.components) find(ch, cls)?.let { return it }
        return null
    }

    private fun diffButton(c: Component): JToggleButton? {
        if (c is JToggleButton && c.text == "Diff") return c
        if (c is Container) for (ch in c.components) diffButton(ch)?.let { return it }
        return null
    }

    @Test
    fun diffToggleRendersUnifiedDiffAndRestores() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display")
        var diffText = ""
        var restored = ""
        SwingUtilities.invokeAndWait {
            FlatLightLaf.setup()
            val view = JavaView("a\nX\nc", diffBaseline = { onResult -> onResult("a\nb\nc") })
            val btn = diffButton(view)!!
            val area = find(view, RSyntaxTextArea::class.java)!!

            btn.doClick()
            diffText = area.text
            btn.doClick()
            restored = area.text
        }
        assertTrue(diffText.contains("  a")) { diffText }
        assertTrue(diffText.contains("- b")) { diffText }
        assertTrue(diffText.contains("+ X")) { diffText }
        assertEquals("a\nX\nc", restored)
    }
}
