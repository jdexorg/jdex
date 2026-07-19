package io.github.nitanmarcel.jdex.ui

import com.formdev.flatlaf.extras.components.FlatTriStateCheckBox
import com.formdev.flatlaf.extras.components.FlatTriStateCheckBox.State
import io.github.nitanmarcel.jdex.project.CodeNav
import io.github.nitanmarcel.jdex.project.CodeSync
import io.github.nitanmarcel.jdex.project.Symbol
import io.github.nitanmarcel.jdex.project.SyncTarget
import io.github.nitanmarcel.jdex.project.Syntax
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke

class JavaView(
    code: String,
    private val sync: CodeSync = CodeSync.EMPTY,
    private val onCaret: (target: SyncTarget) -> Unit = {},
    syncInitial: State = State.UNSELECTED,
    private val onSyncToggle: (State) -> Unit = {},
    private val diffBaseline: ((onResult: (String?) -> Unit) -> Unit)? = null,
    private val nav: CodeNav = CodeNav.EMPTY,
    private val onDefinition: (Symbol) -> Unit = {},
    private val onFindUsages: (Symbol) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val jdecCode = code
    private val area = CodeTextArea(code, Syntax.JAVA)
    private val findBar = FindBar()
    private val syncColor get() = UiColors.alpha(UiColors.accent(), 80)
    private var highlightTag: Any? = null
    private val diffButton = JToggleButton("Diff").apply {
        toolTipText = "Toggle a diff against the plain-Java output (what deobfuscation changed)"
    }
    private var baseline: String? = null
    private val diffTags = ArrayList<Any>()
    private var diffOn = false
    private val syncCheck = FlatTriStateCheckBox("Sync caret", syncInitial).apply {
        toolTipText = "Off  ·  checked = exact sync  ·  filled = also snap to nearest line"
    }

    private val syncOn get() = syncCheck.state != State.UNSELECTED
    private val approx get() = syncCheck.state == State.INDETERMINATE

    init {
        add(RTextScrollPane(area), BorderLayout.CENTER)
        add(findBar, BorderLayout.SOUTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
            add(syncCheck)
            if (diffBaseline != null) add(diffButton)
        }, BorderLayout.NORTH)

        diffBaseline?.let { fn ->
            diffButton.addActionListener { if (diffButton.isSelected) enterDiff(fn) else exitDiff() }
        }

        val shortcut = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        area.inputMap.put(KeyStroke.getKeyStroke('F'.code, shortcut), "find")
        area.actionMap.put("find", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = findBar.open()
        })

        syncCheck.addActionListener {
            onSyncToggle(syncCheck.state)
            if (!syncOn) clearHighlight()
        }
        area.addCaretListener { e ->
            if (diffOn || !syncOn) return@addCaretListener
            val javaLine = runCatching { area.getLineOfOffset(e.dot) + 1 }.getOrNull() ?: return@addCaretListener
            sync.locate(javaLine, approx)?.let { onCaret(it) }
        }

        area.popupMenu = null
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jdexGotoDef")
        area.actionMap.put("jdexGotoDef", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { symbolAt(area.caretPosition)?.let(onDefinition) }
        })
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0), "jdexFindUsages")
        area.actionMap.put("jdexFindUsages", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { symbolAt(area.caretPosition)?.let(onFindUsages) }
        })
        area.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showMenu(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && (e.clickCount == 2 || e.modifiersEx and shortcut != 0)) {
                    offsetAt(e)?.let { symbolAt(it) }?.let(onDefinition)
                }
            }
        })
    }

    private fun offsetAt(e: MouseEvent): Int? = runCatching { area.viewToModel2D(e.point) }.getOrNull()?.takeIf { it >= 0 }

    private fun symbolAt(offset: Int): Symbol? {
        if (diffOn) return null
        val line = runCatching { area.getLineOfOffset(offset) }.getOrNull() ?: return null
        return nav.symbolAt(line + 1, offset - area.getLineStartOffset(line))
    }

    private fun showMenu(e: MouseEvent) {
        val symbol = offsetAt(e)?.let { symbolAt(it) }
        JPopupMenu().apply {
            add(JMenuItem("Go to Definition").apply { isEnabled = symbol != null; addActionListener { symbol?.let(onDefinition) } })
            add(JMenuItem("Find Usages").apply { isEnabled = symbol != null; addActionListener { symbol?.let(onFindUsages) } })
            addSeparator()
            add(JMenuItem("Copy").apply { isEnabled = area.selectedText != null; addActionListener { area.copy() } })
            add(JMenuItem("Select All").apply { addActionListener { area.selectAll() } })
        }.show(area, e.x, e.y)
    }

    fun setSyncState(state: State) {
        if (syncCheck.state == state) return
        syncCheck.state = state
        if (!syncOn) clearHighlight()
    }

    fun followTo(target: SyncTarget) {
        if (diffOn || !syncOn) return
        val javaLine = sync.javaLineFor(target, approx) ?: return
        val line = javaLine - 1
        if (line < 0 || line >= area.lineCount) return
        clearHighlight()
        highlightTag = runCatching { area.addLineHighlight(line, syncColor) }.getOrNull()
        runCatching { area.modelToView(area.getLineStartOffset(line))?.let { area.scrollRectToVisible(it) } }
    }

    fun revealTo(target: SyncTarget) {
        if (diffOn) return
        val javaLine = sync.javaLineFor(target, true) ?: return
        val line = (javaLine - 1).coerceIn(0, area.lineCount - 1)
        clearHighlight()
        highlightTag = runCatching { area.addLineHighlight(line, syncColor) }.getOrNull()
        runCatching {
            val offset = area.getLineStartOffset(line)
            area.caretPosition = offset
            area.modelToView(offset)?.let { area.scrollRectToVisible(it) }
        }
        area.requestFocusInWindow()
    }

    private fun clearHighlight() {
        highlightTag?.let { runCatching { area.removeLineHighlight(it) } }
        highlightTag = null
    }

    private fun enterDiff(baselineFn: (onResult: (String?) -> Unit) -> Unit) {
        baseline?.let { renderDiff(it); return }
        diffButton.isEnabled = false
        diffButton.text = "Diff…"
        baselineFn { java ->
            diffButton.isEnabled = true
            diffButton.text = "Diff"
            if (java == null) { diffButton.isSelected = false } else { baseline = java; renderDiff(java) }
        }
    }

    private fun renderDiff(java: String) {
        diffOn = true
        clearHighlight()
        syncCheck.isEnabled = false
        val d = UnifiedDiff.of(java, jdecCode)
        area.text = d.text
        area.caretPosition = 0
        clearDiffHighlights()
        d.addedLines.forEach { runCatching { area.addLineHighlight(it, UiColors.diffAdded()) }.getOrNull()?.let(diffTags::add) }
        d.removedLines.forEach { runCatching { area.addLineHighlight(it, UiColors.diffRemoved()) }.getOrNull()?.let(diffTags::add) }
    }

    private fun exitDiff() {
        diffOn = false
        clearDiffHighlights()
        area.text = jdecCode
        area.caretPosition = 0
        syncCheck.isEnabled = true
    }

    private fun clearDiffHighlights() {
        diffTags.forEach { runCatching { area.removeLineHighlight(it) } }
        diffTags.clear()
    }

    private inner class FindBar : JPanel(BorderLayout()) {
        private val field = JTextField()
        private val status = JLabel(" ")

        init {
            isVisible = false
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            add(JLabel("Find: "), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
            add(JPanel().apply {
                add(status)
                add(JButton("Prev").apply { addActionListener { find(false) } })
                add(JButton("Next").apply { addActionListener { find(true) } })
                add(JButton("✕").apply { addActionListener { close() } })
            }, BorderLayout.EAST)
            field.addActionListener { find(true) }
            field.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
            field.actionMap.put("close", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = close()
            })
        }

        fun open() {
            isVisible = true
            revalidate()
            field.requestFocusInWindow()
            field.selectAll()
        }

        private fun close() {
            isVisible = false
            revalidate()
            area.requestFocusInWindow()
        }

        private fun find(forward: Boolean) {
            val query = field.text
            if (query.isEmpty()) return
            val context = SearchContext(query).apply { searchForward = forward; matchCase = false }
            val result = SearchEngine.find(area, context)
            status.text = if (result.wasFound()) " " else "Not found"
        }
    }
}
