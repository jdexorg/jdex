package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.FrameworkStubs
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.constructObject
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

internal fun emuArg(v: Any?): Any? = when (val u = emuUnwrap(v)) {
    is Long -> if (u in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) u.toInt() else u
    else -> u
}

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

    fun set(name: String, value: Any?) { obj.fields[fieldKey(name)] = emuArg(value) }

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

    private fun fieldKey(name: String): String {
        var cur: String? = type
        while (cur != null) {
            val ci = src.classInfo(cur) ?: break
            ci.fields.firstOrNull { it.ref.name == name }?.let { return it.ref.key }
            cur = ci.superType
        }
        return "$type.$name"
    }
}
