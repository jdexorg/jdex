package io.github.nitanmarcel.jdex.ui

import io.github.nitanmarcel.jdex.debug.NativeDebug
import io.github.nitanmarcel.jdex.debug.NdkLldb
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.io.File
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

object DebugConfigDialog {

    fun show(owner: Window?, abi: String): NativeDebug? {
        val dialog = JDialog(owner, "Native debugger", Dialog.ModalityType.APPLICATION_MODAL)
        var result: NativeDebug? = null

        val managed = JRadioButton("Managed — jdex pushes & runs lldb-server", true)
        val remote = JRadioButton("Remote — connect to a gdb-remote server you started")
        ButtonGroup().apply { add(managed); add(remote) }

        val ndkField = JTextField(NdkLldb.defaultNdkRoot()?.absolutePath ?: "")
        val hostField = JTextField("127.0.0.1")
        val portField = JTextField("5039")
        val status = JLabel(" ")
        val ok = JButton("Attach")

        fun refresh() {
            val m = managed.isSelected
            ndkField.isEnabled = m; hostField.isEnabled = !m; portField.isEnabled = !m
            if (m) {
                val path = ndkField.text.trim().takeIf { it.isNotEmpty() }?.let { NdkLldb.lldbServer(File(it), abi) }
                ok.isEnabled = path != null
                status.text = if (path != null) "lldb-server ($abi) found" else "no lldb-server for $abi under this NDK"
            } else {
                ok.isEnabled = hostField.text.isNotBlank() && portField.text.trim().toIntOrNull() != null
                status.text = " "
            }
        }
        val watcher = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        }
        ndkField.document.addDocumentListener(watcher)
        hostField.document.addDocumentListener(watcher)
        portField.document.addDocumentListener(watcher)
        managed.addActionListener { refresh() }
        remote.addActionListener { refresh() }

        val gbc = GridBagConstraints().apply { insets = Insets(3, 4, 3, 4); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; gridwidth = 2; gridx = 0 }
        val form = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 12, 4, 12)
            add(managed, gbc.apply { gridy = 0 })
            add(JLabel("NDK root:"), GridBagConstraints().apply { gridx = 0; gridy = 1; insets = Insets(3, 22, 3, 4) })
            add(ndkField, GridBagConstraints().apply { gridx = 1; gridy = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 4, 3, 4) })
            add(remote, gbc.apply { gridy = 2 })
            add(JLabel("Host:"), GridBagConstraints().apply { gridx = 0; gridy = 3; insets = Insets(3, 22, 3, 4) })
            add(hostField, GridBagConstraints().apply { gridx = 1; gridy = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 4, 3, 4) })
            add(JLabel("Port:"), GridBagConstraints().apply { gridx = 0; gridy = 4; insets = Insets(3, 22, 3, 4) })
            add(portField, GridBagConstraints().apply { gridx = 1; gridy = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 4, 3, 4) })
            add(status, gbc.apply { gridy = 5 })
        }

        ok.addActionListener {
            result = if (managed.isSelected) {
                NdkLldb.lldbServer(File(ndkField.text.trim()), abi)?.let { NativeDebug.Managed(it) }
            } else {
                portField.text.trim().toIntOrNull()?.let { NativeDebug.Remote(hostField.text.trim(), it) }
            }
            if (result != null) dialog.dispose()
        }
        val cancel = JButton("Cancel").apply { addActionListener { dialog.dispose() } }

        dialog.contentPane.add(form, BorderLayout.CENTER)
        dialog.contentPane.add(JPanel().apply { add(ok); add(cancel) }, BorderLayout.SOUTH)
        refresh()
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return result
    }
}
