package io.github.nitanmarcel.jdex.ui

import io.github.nitanmarcel.jdex.project.EngineConfig
import io.github.nitanmarcel.jdex.project.EnginePass
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Window
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel

object EngineConfigDialog {

    private val prefs = Preferences.userRoot().node("jdex/ui/engineconfig")

    fun saved(): EngineConfig = EngineConfig(
        emulatorEnabled = prefs.getBoolean("emulator", true),
        decryptStringsAtStartup = prefs.getBoolean("decryptStartup", false),
        passes = EnginePass.entries.filter { prefs.getBoolean("pass.${it.id}", true) }.toSet(),
        codeCleanup = prefs.getBoolean("cleanup", true),
    )

    private fun persist(cfg: EngineConfig) {
        prefs.putBoolean("emulator", cfg.emulatorEnabled)
        prefs.putBoolean("decryptStartup", cfg.decryptStringsAtStartup)
        prefs.putBoolean("cleanup", cfg.codeCleanup)
        EnginePass.entries.forEach { prefs.putBoolean("pass.${it.id}", it in cfg.passes) }
    }

    fun show(owner: Window?): EngineConfig {
        var result = saved()
        val dialog = JDialog(owner, "Engine configuration", Dialog.ModalityType.APPLICATION_MODAL)

        val emulator = JCheckBox("Enable deobfuscation", result.emulatorEnabled)
        val startup = JCheckBox("Decrypt strings at startup", result.decryptStringsAtStartup)
        val cleanup = JCheckBox("Code cleanup", result.codeCleanup)
        val passBoxes = EnginePass.entries.associateWith { JCheckBox(it.label, it in result.passes) }

        fun refresh() {
            val on = emulator.isSelected
            startup.isEnabled = on
            cleanup.isEnabled = on
            passBoxes.values.forEach { it.isEnabled = on }
        }
        emulator.addActionListener { refresh() }

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
            add(emulator)
            add(Box.createVerticalStrut(6))
            add(JLabel("Deobfuscation:"))
            passBoxes.values.forEach { add(it.apply { border = BorderFactory.createEmptyBorder(0, 16, 0, 0) }) }
            add(cleanup.apply { border = BorderFactory.createEmptyBorder(0, 16, 0, 0) })
            add(Box.createVerticalStrut(8))
            add(startup)
        }

        val ok = JButton("Open").apply {
            addActionListener {
                result = EngineConfig(emulator.isSelected, startup.isSelected, passBoxes.filterValues { it.isSelected }.keys.toSet(), cleanup.isSelected)
                persist(result)
                dialog.dispose()
            }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dialog.dispose() } }

        dialog.contentPane.add(body, BorderLayout.CENTER)
        dialog.contentPane.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(cancel); add(ok) }, BorderLayout.SOUTH)
        dialog.rootPane.defaultButton = ok
        refresh()
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return result
    }
}
