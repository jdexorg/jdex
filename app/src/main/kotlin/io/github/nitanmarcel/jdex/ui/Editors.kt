package io.github.nitanmarcel.jdex.ui

import io.github.nitanmarcel.jdex.project.BinaryContent
import io.github.nitanmarcel.jdex.project.CodeContent
import io.github.nitanmarcel.jdex.project.Content
import io.github.nitanmarcel.jdex.project.TextContent
import org.exbin.auxiliary.binary_data.array.ByteArrayData
import org.exbin.bined.EditMode
import org.exbin.bined.swing.basic.CodeArea
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import javax.swing.JComponent

object Editors {

    fun component(content: Content): JComponent = when (content) {
        is TextContent -> RTextScrollPane(CodeTextArea(content.text, content.syntax))

        is BinaryContent -> CodeArea().apply {
            editMode = EditMode.READ_ONLY
            setCodeFont(Font(Font.MONOSPACED, Font.PLAIN, 12))
            setContentData(ByteArrayData(content.bytes))
            resetColors()
        }

        is CodeContent -> error("CodeContent is opened through the loading dialog")
    }
}
