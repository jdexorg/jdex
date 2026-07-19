package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.input.DexInputSource
import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import java.io.File

object FrameworkStubs {

    val source: MethodSource? by lazy { loadFromResource() }

    fun method(classDesc: String, shortId: String): DexMethod? = source?.method(classDesc, shortId)

    fun hasClass(classDesc: String): Boolean = source?.classInfo(classDesc) != null

    fun call(android: AndroidStubs, classDesc: String, shortId: String, receiver: Any?, args: List<Any?>): Any? {
        val m = method(classDesc, shortId)
            ?: throw IllegalArgumentException("no framework member $classDesc->$shortId (check the signature)")
        val r = runCatching {
            if (m.isStatic) android.callStatic(m.ref, args) else android.callInstance(m.ref, receiver, args)
        }.getOrElse { UnknownVal(m.ref.returnType) }
        return if (r === NotHandled) UnknownVal(m.ref.returnType) else r
    }

    private fun loadFromResource(): MethodSource? = runCatching {
        val stream = FrameworkStubs::class.java.getResourceAsStream("/framework/android-13-stub.jar") ?: return null
        val tmp = File.createTempFile("jdex-android-stub", ".jar").apply { deleteOnExit() }
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            DexInputSource.loadFramework(tmp)
        } finally {
            tmp.delete()
        }
    }.getOrNull()
}
