package io.github.nitanmarcel.jdex.project

import jadx.api.JavaClass
import jadx.api.plugins.input.data.AccessFlags
import jadx.api.plugins.input.data.AccessFlagsScope
import jadx.api.plugins.input.data.ICodeReader
import jadx.api.plugins.input.data.IFieldData
import jadx.api.plugins.input.data.IMethodData
import jadx.api.plugins.input.data.ISeqConsumer
import jadx.api.plugins.input.insns.InsnData
import jadx.api.plugins.input.insns.InsnIndexType
import jadx.api.plugins.input.insns.custom.IArrayPayload
import jadx.api.plugins.input.insns.custom.ISwitchPayload
import java.lang.reflect.Array as ReflectArray

object BytecodeWriter {

    private const val MNEMONIC_WIDTH = 20

    fun forClass(cls: JavaClass, resources: Map<Int, String>): String {
        val data = cls.classNode?.clsData ?: return "Class: L${cls.rawName.replace('.', '/')};\n"
        val out = StringBuilder()
        out.appendLine("Class: ${data.type}")
        out.appendLine("AccessFlags: ${AccessFlags.format(data.accessFlags, AccessFlagsScope.CLASS).trim()}")
        out.appendLine("SuperType: ${data.superType}")
        if (data.interfacesTypes.isNotEmpty()) {
            out.appendLine("Interfaces: [${data.interfacesTypes.joinToString(", ")}]")
        }

        val fields = mutableListOf<Pair<String, String>>()
        val methods = StringBuilder()
        data.visitFieldsAndMethods(
            ISeqConsumer<IFieldData> { field ->
                fields.add("${modifiers(field.accessFlags, AccessFlagsScope.FIELD)}${field.name}" to field.type)
            },
            ISeqConsumer<IMethodData> { method -> appendMethod(methods, method, resources) },
        )

        if (fields.isNotEmpty()) {
            out.appendLine()
            out.appendLine("# fields")
            val width = fields.maxOf { it.first.length }
            fields.forEach { (declaration, type) -> out.appendLine(".field ${declaration.padEnd(width)} : $type") }
        }
        out.append(methods)
        return out.toString()
    }

    private fun appendMethod(out: StringBuilder, method: IMethodData, resources: Map<Int, String>) {
        val ref = method.methodRef.apply { load() }
        out.appendLine()
        out.appendLine(".method ${modifiers(method.accessFlags, AccessFlagsScope.METHOD)}${ref.name}(${ref.argTypes.joinToString("")})${ref.returnType}")
        val reader = method.codeReader
        if (reader == null) {
            out.appendLine(".end method")
            return
        }

        out.appendLine("    .registers ${reader.registersCount}")
        appendTries(out, reader)

        val isStatic = AccessFlags.hasFlag(method.accessFlags, AccessFlags.STATIC)
        val insSize = (if (isStatic) 0 else 1) + ref.argTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val paramBase = reader.registersCount - insSize

        val debug = reader.debugInfo
        val lines = debug?.sourceLineMapping ?: emptyMap()
        val localStart = (debug?.localVars ?: emptyList()).groupBy { it.startOffset }
        val localEnd = (debug?.localVars ?: emptyList()).groupBy { it.endOffset }

        out.appendLine()
        val codeBase = reader.codeOffset
        var first = true
        reader.visitInstructions { insn ->
            insn.decode()
            val offset = insn.offset
            lines[offset]?.let {
                if (!first) out.appendLine()
                out.appendLine("    .line $it")
            }
            localEnd[offset]?.forEach { out.appendLine("    .end local ${reg(it.regNum, paramBase)}") }
            localStart[offset]?.forEach {
                val kind = if (it.isMarkedAsParameter) ".param" else ".local"
                out.appendLine("    $kind ${reg(it.regNum, paramBase)}, \"${it.name}\":${it.type}")
            }
            out.appendLine("    ${formatInsn(insn, codeBase, resources, paramBase)}")
            first = false
        }
        out.appendLine(".end method")
    }

    private fun appendTries(out: StringBuilder, reader: ICodeReader) {
        for (aTry in reader.tries) {
            val catch = aTry.catch
            val range = "%04x .. %04x".format(aTry.startOffset, aTry.endOffset)
            catch.types.forEachIndexed { i, type ->
                out.appendLine("    .catch $type { $range } -> %04x".format(catch.handlers[i]))
            }
            if (catch.catchAllHandler >= 0) {
                out.appendLine("    .catchall { $range } -> %04x".format(catch.catchAllHandler))
            }
        }
    }

    private fun formatInsn(insn: InsnData, codeBase: Int, resources: Map<Int, String>, paramBase: Int): String {
        val name = insn.opcode?.name ?: ""
        val mnemonic = insn.opcodeMnemonic
        val fileOffset = codeBase + insn.offset * 2

        payload(insn)?.let { return "%08x: %-18s %04x: %s".format(fileOffset, "%04x".format(insn.rawOpcodeUnit), insn.offset, it) }

        val prefix = "%08x: %-18s %04x:".format(fileOffset, hex(insn.byteCode), insn.offset)
        val braces = name.startsWith("INVOKE") || name.startsWith("FILLED_NEW_ARRAY")
        val registers = registers(insn, braces, name.endsWith("_RANGE"), paramBase)
        val operand = when {
            name == "CONST_METHOD_HANDLE" -> "${insn.indexAsMethodHandle.type}"
            name == "CONST_METHOD_TYPE" -> insn.getIndexAsProto(insn.index).let { "(${it.argTypes.joinToString("")})${it.returnType}" }
            isTarget(name) -> "%04x".format(insn.target)
            insn.indexType == InsnIndexType.CALL_SITE -> insn.indexAsCallSite.values.joinToString(", ")
            insn.indexType == InsnIndexType.METHOD_REF -> insn.indexAsMethod.apply { load() }.let { "${it.parentClassType}->${it.name}(${it.argTypes.joinToString("")})${it.returnType}" }
            insn.indexType == InsnIndexType.FIELD_REF -> insn.indexAsField.let { "${it.parentClassType}->${it.name}:${it.type}" }
            insn.indexType == InsnIndexType.STRING_REF -> "\"${escape(insn.indexAsString)}\""
            insn.indexType == InsnIndexType.TYPE_REF -> insn.indexAsType
            isLiteral(name) -> literal(insn.literal)
            else -> null
        }
        val operands = listOfNotNull(registers.ifEmpty { null }, operand).joinToString(", ")
        val resource = if (isLiteral(name)) resources[insn.literal.toInt()]?.let { " # @$it" } ?: "" else ""
        return "$prefix ${mnemonic.padEnd(MNEMONIC_WIDTH)} $operands${comment(insn)}$resource".trimEnd()
    }

    private fun payload(insn: InsnData): String? = when (insn.rawOpcodeUnit) {
        0x0100, 0x0200 -> {
            val name = if (insn.rawOpcodeUnit == 0x0100) "packed-switch-payload" else "sparse-switch-payload"
            val switch = insn.payload as? ISwitchPayload
            val cases = switch?.let { it.keys.zip(it.targets.toList()).joinToString(", ") { (k, t) -> "0x${k.toString(16)} -> %04x".format(t) } }
            if (cases != null) "$name {$cases}" else name
        }
        0x0300 -> {
            val array = insn.payload as? IArrayPayload
            if (array != null) "fill-array-data-payload {${arrayValues(array.data)}}" else "fill-array-data-payload"
        }
        else -> null
    }

    private fun arrayValues(data: Any?): String {
        if (data == null) return ""
        val size = ReflectArray.getLength(data)
        return (0 until size).joinToString(", ") {
            when (val v = ReflectArray.get(data, it)) {
                is Byte -> "0x${(v.toInt() and 0xff).toString(16)}"
                is Short -> "0x${(v.toInt() and 0xffff).toString(16)}"
                is Int -> "0x${v.toLong().and(0xffffffffL).toString(16)}"
                is Long -> "0x${v.toULong().toString(16)}"
                else -> v.toString()
            }
        }
    }

    private fun registers(insn: InsnData, braces: Boolean, range: Boolean, paramBase: Int): String {
        val count = insn.regsCount
        if (count == 0) return ""
        val body = if (range) "${reg(insn.getReg(0), paramBase)} .. ${reg(insn.getReg(count - 1), paramBase)}"
        else (0 until count).joinToString(", ") { reg(insn.getReg(it), paramBase) }
        return if (braces) "{$body}" else body
    }

    private fun reg(r: Int, paramBase: Int): String = if (paramBase in 0..r) "p${r - paramBase}" else "v$r"

    private fun comment(insn: InsnData): String = when (insn.indexType) {
        InsnIndexType.METHOD_REF -> " # method@${insn.index.toString(16)}"
        InsnIndexType.FIELD_REF -> " # field@${insn.index.toString(16)}"
        InsnIndexType.STRING_REF -> " # string@${insn.index.toString(16)}"
        InsnIndexType.TYPE_REF -> " # type@${insn.index.toString(16)}"
        InsnIndexType.CALL_SITE -> " # call_site@${insn.index.toString(16)}"
        else -> ""
    }

    private fun isTarget(name: String) = name.startsWith("GOTO") || name.startsWith("IF_") ||
        name == "PACKED_SWITCH" || name == "SPARSE_SWITCH" || name == "FILL_ARRAY_DATA"

    private fun isLiteral(name: String) = name.startsWith("CONST") || name.endsWith("_LIT") || name == "RSUB_INT"

    private fun literal(value: Long): String = if (value < 0) "-0x${(-value).toString(16)}" else "0x${value.toString(16)}"

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private fun hex(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            append("%02x".format(bytes[i].toInt() and 0xff))
            if (i + 1 < bytes.size) append("%02x".format(bytes[i + 1].toInt() and 0xff))
            append(' ')
            i += 2
        }
    }.trim()

    private fun modifiers(flags: Int, scope: AccessFlagsScope): String {
        val text = AccessFlags.format(flags, scope).trim()
        return if (text.isEmpty()) "" else "$text "
    }
}
