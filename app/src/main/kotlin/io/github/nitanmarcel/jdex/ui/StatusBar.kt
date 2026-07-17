package io.github.nitanmarcel.jdex.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSeparator
import javax.swing.SwingConstants

class StatusBar : JPanel(BorderLayout()) {
    private val target = JLabel("")
    private val arch = JLabel("")
    private val position = JLabel("")
    private val sepArch = sep()

    private val progressLabel = JLabel()
    private val progressBar = JProgressBar().apply { preferredSize = Dimension(140, 14) }
    private val progressCancel = JButton("Cancel").apply {
        putClientProperty("JButton.buttonType", "borderless")
        addActionListener { cancelAction?.invoke() }
    }
    private var cancelAction: (() -> Unit)? = null
    private var progressText = ""
    private var foreground = false
    private var backgroundText: String? = null

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UiColors.border()),
            BorderFactory.createEmptyBorder(2, 8, 2, 8),
        )
        val left = JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply { isOpaque = false }
        left.add(progressLabel); left.add(progressBar); left.add(progressCancel)
        left.add(target); left.add(sepArch); left.add(arch)
        add(left, BorderLayout.WEST)
        add(position.apply { horizontalAlignment = SwingConstants.RIGHT }, BorderLayout.EAST)
        muted(target); muted(arch); muted(position)
        render()
        refreshVisibility()
    }

    override fun getPreferredSize(): Dimension {
        val w = super.getPreferredSize().width
        val h = maxOf(progressCancel.preferredSize.height, position.preferredSize.height) + 8
        return Dimension(w, h)
    }

    fun setTarget(text: String) { target.text = text; refreshVisibility() }
    fun setArch(text: String) { arch.text = text; refreshVisibility() }
    fun setPosition(text: String) { position.text = text }

    fun startProgress(text: String, onCancel: () -> Unit) {
        cancelAction = onCancel
        foreground = true
        progressText = text
        progressBar.isIndeterminate = true
        render()
    }

    fun setProgress(current: Int, total: Int, unit: String) {
        if (!foreground) return
        progressBar.isIndeterminate = false
        progressBar.maximum = total
        progressBar.value = current
        progressLabel.text = "$progressText  $current / $total $unit"
    }

    fun stopProgress() {
        cancelAction = null
        foreground = false
        render()
    }

    fun startBackground(text: String) {
        backgroundText = text
        if (!foreground) render()
    }

    fun stopBackground() {
        backgroundText = null
        if (!foreground) render()
    }

    private fun render() {
        when {
            foreground -> {
                progressLabel.text = progressText
                progressBar.isIndeterminate = true
                progressLabel.isVisible = true; progressBar.isVisible = true; progressCancel.isVisible = true
            }
            backgroundText != null -> {
                progressText = backgroundText!!
                progressLabel.text = progressText
                progressBar.isIndeterminate = true
                progressLabel.isVisible = true; progressBar.isVisible = true; progressCancel.isVisible = false
            }
            else -> { progressLabel.isVisible = false; progressBar.isVisible = false; progressCancel.isVisible = false }
        }
        revalidate(); repaint()
    }

    private fun refreshVisibility() {
        target.isVisible = target.text.isNotEmpty()
        arch.isVisible = arch.text.isNotEmpty()
        sepArch.isVisible = target.text.isNotEmpty() && arch.text.isNotEmpty()
    }

    private fun sep() = JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 14) }
    private fun muted(l: JLabel) { l.foreground = UiColors.disabled() }
}
