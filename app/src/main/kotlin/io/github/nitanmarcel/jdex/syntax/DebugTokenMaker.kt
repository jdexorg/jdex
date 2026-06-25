package io.github.nitanmarcel.jdex.syntax

import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMap
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.text.Segment

class DebugTokenMaker : AbstractTokenMaker() {

    override fun getWordsToHighlight(): TokenMap = TokenMap()

    override fun getTokenList(text: Segment, initialTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        val a = text.array
        val off = text.offset
        val end = off + text.count
        if (text.count == 0) { addNullToken(); return firstToken }

        fun emit(s: Int, e: Int, type: Int) { if (e > s) addToken(a, s, e - 1, type, startOffset + (s - off)) }

        var i = off

        if (initialTokenType == Token.LITERAL_STRING_DOUBLE_QUOTE) {
            val s = i
            var closed = false
            while (i < end) {
                if (a[i] == '\\' && i + 1 < end) { i += 2; continue }
                if (a[i] == '"') { i++; closed = true; break }
                i++
            }
            emit(s, i, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
            if (!closed) return firstToken
        }

        var afterColon = false
        while (i < end) {
            val c = a[i]
            when {
                c == ' ' || c == '\t' -> { val s = i; while (i < end && (a[i] == ' ' || a[i] == '\t')) i++; emit(s, i, TokenTypes.WHITESPACE) }
                c == '"' -> {
                    val s = i; i++
                    var closed = false
                    while (i < end) {
                        if (a[i] == '\\' && i + 1 < end) { i += 2; continue }
                        if (a[i] == '"') { i++; closed = true; break }
                        i++
                    }
                    emit(s, i, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
                    if (!closed) return firstToken
                    afterColon = false
                }
                c == '\'' -> {
                    val s = i; i++
                    while (i < end && a[i] != '\'') i++
                    if (i < end) i++
                    emit(s, i, TokenTypes.LITERAL_CHAR); afterColon = false
                }
                c.isDigit() -> { val s = i; while (i < end && (a[i].isLetterOrDigit())) i++; emit(s, i, TokenTypes.LITERAL_NUMBER_DECIMAL_INT); afterColon = false }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < end && (a[i].isLetterOrDigit() || a[i] == '_' || a[i] == '.' || a[i] == '$' || a[i] == '[' || a[i] == ']')) i++
                    val word = String(a, s, i - s)
                    emit(s, i, if (afterColon) TokenTypes.DATA_TYPE else if (word in KEYWORDS) TokenTypes.RESERVED_WORD else TokenTypes.IDENTIFIER)
                    afterColon = false
                }
                c == ':' -> { emit(i, i + 1, TokenTypes.SEPARATOR); i++; afterColon = true }
                c == '=' -> { emit(i, i + 1, TokenTypes.OPERATOR); i++; afterColon = false }
                else -> { emit(i, i + 1, TokenTypes.SEPARATOR); i++; afterColon = false }
            }
        }
        addNullToken()
        return firstToken
    }

    companion object {
        private val KEYWORDS = setOf("null", "true", "false", "void", "id")
    }
}
