package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject

sealed interface ArgInput {
    data class Text(val text: String) : ArgInput
    data class Bool(val value: Boolean) : ArgInput
    data class Elements(val items: List<String>) : ArgInput
    data class Bytes(val bytes: ByteArray) : ArgInput
    object Null : ArgInput
}

fun parseParamTypes(descriptor: String): List<String> {
    val params = descriptor.substringAfter('(').substringBefore(')')
    val out = ArrayList<String>()
    var i = 0
    while (i < params.length) {
        val start = i
        while (params[i] == '[') i++
        i = if (params[i] == 'L') params.indexOf(';', i) + 1 else i + 1
        out.add(params.substring(start, i))
    }
    return out
}

fun humanType(t: String): String = when {
    t == "I" -> "int"; t == "J" -> "long"; t == "S" -> "short"; t == "B" -> "byte"
    t == "C" -> "char"; t == "Z" -> "boolean"; t == "F" -> "float"; t == "D" -> "double"; t == "V" -> "void"
    t.startsWith("[") -> humanType(t.substring(1)) + "[]"
    t.startsWith("L") -> t.removePrefix("L").removeSuffix(";").substringAfterLast('/')
    else -> t
}

private val PRIM_ARRAYS = setOf("[I", "[J", "[B", "[C", "[S", "[Z", "[F", "[D")

fun argValues(paramTypes: List<String>, inputs: List<ArgInput?>): List<Any?> =
    paramTypes.mapIndexed { i, t -> argValue(t, inputs.getOrNull(i)) }

private fun argValue(t: String, input: ArgInput?): Any? {
    val text = (input as? ArgInput.Text)?.text?.trim().orEmpty()
    fun num(): Long = if (text.isEmpty()) 0L else if (text.startsWith("0x")) text.substring(2).toLong(16) else text.toLong()
    return when (t) {
        "I", "S", "B" -> runCatching { num().toInt() }.getOrDefault(0)
        "J" -> runCatching { num() }.getOrDefault(0L)
        "F" -> text.toFloatOrNull() ?: 0f
        "D" -> text.toDoubleOrNull() ?: 0.0
        "Z" -> (input as? ArgInput.Bool)?.value ?: false
        "C" -> if (text.isEmpty()) ' ' else if (text.length == 1) text[0] else runCatching { num().toInt().toChar() }.getOrDefault(' ')
        "Ljava/lang/String;" -> text.ifEmpty { null }
        in PRIM_ARRAYS -> primArray(t, input)
        else -> null
    }
}

private fun primArray(t: String, input: ArgInput?): Any? {
    if (t == "[B" && input is ArgInput.Bytes) return input.bytes
    val items = (input as? ArgInput.Elements)?.items?.filter { it.isNotBlank() }
    if (items.isNullOrEmpty()) return null
    fun n(s: String): Long = s.trim().let { if (it.startsWith("0x")) it.substring(2).toLong(16) else it.toLong() }
    return runCatching {
        when (t) {
            "[B" -> ByteArray(items.size) { n(items[it]).toByte() }
            "[I" -> IntArray(items.size) { n(items[it]).toInt() }
            "[J" -> LongArray(items.size) { n(items[it]) }
            "[S" -> ShortArray(items.size) { n(items[it]).toShort() }
            "[C" -> CharArray(items.size) { items[it].trim().let { s -> if (s.length == 1) s[0] else n(s).toInt().toChar() } }
            "[Z" -> BooleanArray(items.size) { items[it].trim().toBoolean() }
            "[F" -> FloatArray(items.size) { items[it].trim().toFloat() }
            "[D" -> DoubleArray(items.size) { items[it].trim().toDouble() }
            else -> null
        }
    }.getOrNull()
}

fun emulationReceiver(method: DexMethod, source: MethodSource): Any? =
    if (method.isStatic) null
    else runCatching { if (source.classInfo(method.declClass) != null) DvmObject(method.declClass) else null }.getOrNull()
