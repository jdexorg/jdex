package io.github.nitanmarcel.jdex.ui

import io.github.nitanmarcel.jdex.debug.ArgInput
import io.github.nitanmarcel.jdex.debug.argValues
import io.github.nitanmarcel.jdex.debug.humanType
import io.github.nitanmarcel.jdex.debug.parseParamTypes
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

private val PRIM_ARRAYS = setOf("[I", "[J", "[B", "[C", "[S", "[Z", "[F", "[D")

private fun row(label: String, editor: JComponent): JComponent {
    val name = JLabel(label).apply { preferredSize = Dimension(230, preferredSize.height) }
    return JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); add(name); add(editor) }
}

private class ArrayEditor(type: String) {
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val fields = ArrayList<JTextField>()
    private var fileBytes: ByteArray? = null
    private val status = JLabel("")

    init {
        panel.add(rows)
        panel.add(JPanel().apply {
            add(JButton("+").apply { addActionListener { addField() } })
            if (type == "[B") add(JButton("Load file…").apply { addActionListener { loadFile() } })
            add(status)
        })
    }

    private fun addField() {
        fileBytes = null; status.text = ""
        val f = JTextField(6)
        val r = JPanel()
        r.add(f)
        r.add(JButton("–").apply { addActionListener { fields.remove(f); rows.remove(r); rows.revalidate(); rows.repaint() } })
        fields.add(f); rows.add(r); rows.revalidate(); rows.repaint()
    }

    private fun loadFile() {
        val fc = JFileChooser()
        if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            fileBytes = fc.selectedFile.readBytes()
            rows.removeAll(); fields.clear(); rows.revalidate(); rows.repaint()
            status.text = "${fileBytes!!.size} bytes loaded"
        }
    }

    fun input(): ArgInput = fileBytes?.let { ArgInput.Bytes(it) } ?: ArgInput.Elements(fields.map { it.text })
}

object EmulationArgsDialog {

    fun show(parent: Component, descriptor: String): List<Any?>? {
        val types = parseParamTypes(descriptor)
        val suppliers = ArrayList<() -> ArgInput>()
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        for ((i, t) in types.withIndex()) {
            val label = "p$i: ${humanType(t)}"
            when {
                t == "Z" -> {
                    val cb = JCheckBox()
                    suppliers.add { ArgInput.Bool(cb.isSelected) }
                    form.add(row(label, cb))
                }
                t in PRIM_ARRAYS -> {
                    val ed = ArrayEditor(t)
                    suppliers.add { ed.input() }
                    form.add(row(label, ed.panel))
                }
                t == "Ljava/lang/String;" || t.length == 1 -> {
                    val tf = JTextField(16)
                    suppliers.add { ArgInput.Text(tf.text) }
                    form.add(row(label, tf))
                }
                else -> {
                    suppliers.add { ArgInput.Null }
                    form.add(row(label, JLabel("null").apply { isEnabled = false }))
                }
            }
        }

        val ok = JOptionPane.showConfirmDialog(parent, JScrollPane(form), "Start Emulation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return null
        return argValues(types, suppliers.map { it() })
    }
}
