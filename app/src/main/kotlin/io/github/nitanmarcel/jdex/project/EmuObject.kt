package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.FrameworkStubs
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.constructObject
import io.github.nitanmarcel.jdex.exec.model.FieldRef
import io.github.nitanmarcel.jdex.exec.runtime.DvmObject
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import io.github.nitanmarcel.jdex.exec.worldVm
import org.graalvm.polyglot.Value

fun isEmuObject(v: Any?): Boolean = v is EmuObject

fun emuUnwrap(v: Any?): Any? = when {
    v is EmuObject -> v.obj
    v is Value && v.canInvokeMember("_jdex_unwrap") -> runCatching {
        val h = v.invokeMember("_jdex_unwrap")
        if (h.isHostObject) (h.asHostObject<Any?>() as? EmuObject)?.obj ?: v else v
    }.getOrDefault(v)
    else -> v
}

internal fun emuArg(v: Any?): Any? = pyToEmu(v, null)

internal fun pyToEmu(v: Any?, type: String? = null): Any? = when (val u = emuUnwrap(v)) {
    is Value -> valueToEmu(u, type)
    is List<*> -> seqToArray(u.size, type) { u[it] }
    is Long -> narrowLong(u, type)
    else -> u
}

private fun valueToEmu(v: Value, type: String?): Any? = when {
    v.isNull -> null
    v.isBoolean -> v.asBoolean()
    v.isString -> v.asString()
    v.hasBufferElements() -> ByteArray(v.bufferSize.toInt()) { v.readBufferByte(it.toLong()) }
    v.hasArrayElements() -> seqToArray(v.arraySize.toInt(), type) { v.getArrayElement(it.toLong()) }
    v.hasHashEntries() -> hashToMap(v)
    v.fitsInInt() -> if (type == "J") v.asLong() else v.asInt()
    v.fitsInLong() -> v.asLong()
    v.fitsInDouble() -> if (type == "F") v.asFloat() else v.asDouble()
    v.hasIterator() -> iterToArray(v, type)
    else -> v
}

private inline fun seqToArray(n: Int, type: String?, elem: (Int) -> Any?): Any = when (type) {
    "[B" -> ByteArray(n) { num(elem(it)).toByte() }
    "[I" -> IntArray(n) { num(elem(it)).toInt() }
    "[J" -> LongArray(n) { num(elem(it)).toLong() }
    "[S" -> ShortArray(n) { num(elem(it)).toShort() }
    "[C" -> CharArray(n) { asChar(elem(it)) }
    "[Z" -> BooleanArray(n) { asBool(elem(it)) }
    "[F" -> FloatArray(n) { num(elem(it)).toFloat() }
    "[D" -> DoubleArray(n) { num(elem(it)).toDouble() }
    else -> Array(n) { pyToEmu(elem(it), type?.takeIf { t -> t.startsWith("[") }?.substring(1)) }
}

private fun iterToArray(v: Value, type: String?): Any {
    val out = ArrayList<Any?>()
    val it = v.iterator
    while (it.hasIteratorNextElement()) out.add(it.iteratorNextElement)
    return seqToArray(out.size, type) { out[it] }
}

private fun hashToMap(v: Value): LinkedHashMap<Any?, Any?> {
    val m = LinkedHashMap<Any?, Any?>()
    val keys = v.hashKeysIterator
    while (keys.hasIteratorNextElement()) {
        val k = keys.iteratorNextElement
        m[pyToEmu(k)] = pyToEmu(v.getHashValue(k))
    }
    return m
}

private fun num(v: Any?): Number = when (v) {
    is Number -> v
    is Value -> if (v.fitsInLong()) v.asLong() else v.asDouble()
    is Char -> v.code
    is Boolean -> if (v) 1 else 0
    else -> 0
}

private fun asChar(v: Any?): Char = when (v) {
    is Char -> v
    is Value -> if (v.isString) v.asString().firstOrNull() ?: '\u0000' else v.asInt().toChar()
    else -> num(v).toInt().toChar()
}

private fun asBool(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Value -> if (v.isBoolean) v.asBoolean() else v.fitsInInt() && v.asInt() != 0
    else -> num(v).toInt() != 0
}

private fun narrowLong(u: Long, type: String?): Any =
    if (type == "J") u else if (u in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) u.toInt() else u

internal fun pyFriendly(v: Any?): Any? = when (v) {
    is UnknownVal -> null
    is DvmObject -> v.type
    else -> v
}

fun constructEmuObject(src: MethodSource, world: EmuWorld, classDesc: String, ctorSig: String?, args: List<Any?>): EmuObject =
    EmuObject(constructObject(src, world, classDesc, ctorSig, args.map(::emuArg)), src, world)

class EmuObject(val obj: DvmObject, private val src: MethodSource, private val world: EmuWorld) {

    val type: String get() = obj.type

    fun get(name: String): Any? = obj.fields[fieldKey(name)].let { if (it is DvmObject) EmuObject(it, src, world) else it }

    fun set(name: String, value: Any?) {
        val ref = fieldRefOf(name)
        obj.fields[ref?.key ?: "$type.$name"] = pyToEmu(value, ref?.type)
    }

    fun call(shortId: String, args: List<Any?>): Any? {
        val m = src.method(type, shortId)
        if (m != null) {
            val r = worldVm(src, world).invoke(m, args.map(::emuArg), if (m.isStatic) null else obj)
            return if (r is DvmObject) EmuObject(r, src, world) else r
        }
        if (world.android.isFrameworkClass(type)) {
            val r = FrameworkStubs.call(world.android, type, shortId, obj, args.map(::emuArg))
            return if (r is DvmObject) EmuObject(r, src, world) else r
        }
        throw IllegalArgumentException("no $shortId on $type")
    }

    fun fields(): Map<String, Any?> = obj.fields.entries.associate { (k, v) -> k.substringAfterLast('.') to v }

    private fun fieldKey(name: String): String = fieldRefOf(name)?.key ?: "$type.$name"

    private fun fieldRefOf(name: String): FieldRef? {
        var cur: String? = type
        while (cur != null) {
            val ci = src.classInfo(cur) ?: break
            ci.fields.firstOrNull { it.ref.name == name }?.let { return it.ref }
            cur = ci.superType
        }
        return null
    }
}
