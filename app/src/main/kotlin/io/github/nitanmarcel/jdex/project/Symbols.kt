package io.github.nitanmarcel.jdex.project

enum class SymbolKind { TYPE, METHOD, FIELD, STRING, RESOURCE, LOCAL }

class Usage(val display: String, val rawName: String, val shortId: String?, val fieldName: String?, val dexPc: Int? = null)

data class Symbol(val kind: SymbolKind, val text: String) {

    val resourceType: String? get() = if (kind == SymbolKind.RESOURCE) text.removePrefix("@").substringBefore('/') else null

    val resourceName: String? get() = if (kind == SymbolKind.RESOURCE) text.substringAfterLast('/') else null

    val declaringType: String?
        get() = when (kind) {
            SymbolKind.TYPE -> text
            SymbolKind.METHOD, SymbolKind.FIELD -> text.substringBefore("->", "").ifEmpty { null }
            SymbolKind.STRING, SymbolKind.RESOURCE, SymbolKind.LOCAL -> null
        }

    val member: String?
        get() = if (kind == SymbolKind.METHOD || kind == SymbolKind.FIELD) text.substringAfter("->", "").ifEmpty { null } else null

    fun declaringClassName(): String? =
        declaringType?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')

    val renameKey: String?
        get() = when (kind) {
            SymbolKind.TYPE, SymbolKind.METHOD, SymbolKind.LOCAL -> text
            SymbolKind.FIELD -> text.substringBefore(':')
            SymbolKind.STRING, SymbolKind.RESOURCE -> null
        }

    val simpleName: String?
        get() = when (kind) {
            SymbolKind.TYPE -> text.removePrefix("L").removeSuffix(";").substringAfterLast('/')
            SymbolKind.METHOD -> member?.substringBefore('(')
            SymbolKind.FIELD -> member?.substringBefore(':')
            SymbolKind.LOCAL -> text.substringAfterLast('#')
            SymbolKind.STRING, SymbolKind.RESOURCE -> null
        }

    companion object {
        private val STRING = Regex(""""(\\.|[^"\\])*"""")
        private val REF = Regex("""L[\w/$]+;->[^ ,@]+""")
        private val RESOURCE = Regex("""@[\w.]+/[\w.$]+""")
        private val TYPE = Regex("""L[\w/$]+;""")

        fun at(line: String, col: Int): Symbol? {
            STRING.findAll(line).forEach { if (col in it.range.first..it.range.last + 1) return Symbol(SymbolKind.STRING, it.value.trim('"')) }
            REF.findAll(line).forEach { m ->
                if (col in m.range.first..m.range.last + 1) {
                    return Symbol(if ('(' in m.value) SymbolKind.METHOD else SymbolKind.FIELD, m.value)
                }
            }
            RESOURCE.findAll(line).forEach { if (col in it.range.first..it.range.last + 1) return Symbol(SymbolKind.RESOURCE, it.value) }
            TYPE.findAll(line).forEach { if (col in it.range.first..it.range.last + 1) return Symbol(SymbolKind.TYPE, it.value) }
            return null
        }
    }
}
