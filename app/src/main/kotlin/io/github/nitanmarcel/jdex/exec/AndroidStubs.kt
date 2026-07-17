package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.runtime.UNKNOWN

object NotHandled

class StubNotImplemented(val descriptor: String) :
    RuntimeException("only have a stub for $descriptor — register an implementation (e.g. via the scripting API)")

fun interface StubHandler {
    fun invoke(receiver: Any?, args: List<Any?>): Any?
}

class AndroidEnv(
    sdkInt: Int = 33,
    release: String = "13",
    model: String = "Pixel 6",
    manufacturer: String = "Google",
    brand: String = "google",
    device: String = "oriole",
    product: String = "oriole",
    fingerprint: String = "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys",
) {
    private val fields: Map<String, Any?> = mapOf(
        "Landroid/os/Build\$VERSION;.SDK_INT" to sdkInt,
        "Landroid/os/Build\$VERSION;.RELEASE" to release,
        "Landroid/os/Build;.MODEL" to model,
        "Landroid/os/Build;.MANUFACTURER" to manufacturer,
        "Landroid/os/Build;.BRAND" to brand,
        "Landroid/os/Build;.DEVICE" to device,
        "Landroid/os/Build;.PRODUCT" to product,
        "Landroid/os/Build;.FINGERPRINT" to fingerprint,
        "Landroid/os/Build;.SERIAL" to "unknown",
        "Landroid/os/Build;.HARDWARE" to device,
    )

    fun field(declClass: String, name: String): Any? {
        val key = "$declClass.$name"
        return if (key in fields) fields[key] else NotHandled
    }
}

class AndroidStubs(val env: AndroidEnv = AndroidEnv()) {

    private val methods = HashMap<String, StubHandler>()
    private val fields = HashMap<String, Any?>()

    var syntheticCaller: String? = null

    init { registerBuiltins() }

    fun clear() {
        methods.clear()
        fields.clear()
        syntheticCaller = null
        registerBuiltins()
    }

    fun registerMethod(classDesc: String, name: String, handler: StubHandler) {
        methods["$classDesc->$name"] = handler
    }

    fun registerField(classDesc: String, name: String, value: Any?) {
        fields["$classDesc.$name"] = value
    }

    fun isFrameworkClass(declClass: String): Boolean = FRAMEWORK_PREFIXES.any { declClass.startsWith(it) }

    fun callStatic(ref: MethodRef, args: List<Any?>): Any? = dispatch(ref, null, args)

    fun callInstance(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? = dispatch(ref, receiver, args)

    fun field(declClass: String, name: String): Any? {
        val k = "$declClass.$name"
        if (k in fields) return fields[k]
        return env.field(declClass, name)
    }

    private fun dispatch(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? {
        methods["${ref.declClass}->${ref.name}"]?.let { return it.invoke(receiver, args) }
        if (isFrameworkClass(ref.declClass)) throw StubNotImplemented("${ref.declClass}->${ref.shortId}")
        return NotHandled
    }

    private fun registerBuiltins() {
        registerMethod("Landroid/util/Base64;", "encodeToString") { _, a -> base64Encode(a, toStr = true) }
        registerMethod("Landroid/util/Base64;", "encode") { _, a -> base64Encode(a, toStr = false) }
        registerMethod("Landroid/util/Base64;", "decode") { _, a -> base64Decode(a) }
        for (m in listOf("d", "e", "i", "w", "v", "wtf", "println")) registerMethod("Landroid/util/Log;", m) { _, _ -> 0 }
        registerMethod("Landroid/util/Log;", "isLoggable") { _, _ -> false }
        registerMethod("Landroid/util/Log;", "getStackTraceString") { _, _ -> "" }
        for (cls in listOf("Ljava/lang/Thread;", "Ljava/lang/Throwable;", "Ljava/lang/Exception;", "Ljava/lang/RuntimeException;"))
            registerMethod(cls, "getStackTrace") { _, _ -> synthStack() }
    }

    private fun synthStack(): Any? {
        val c = syntheticCaller ?: return NotHandled
        return Array(16) { StackTraceElement(c, "m$it", "Source.java", it + 1) }
    }

    private fun base64Encode(args: List<Any?>, toStr: Boolean): Any? {
        val data = args.getOrNull(0) as? ByteArray ?: return UNKNOWN
        val e = encoder((args.getOrNull(1) as? Int) ?: 0)
        return if (toStr) e.encodeToString(data) else e.encode(data)
    }

    private fun base64Decode(args: List<Any?>): Any? {
        val d = decoder((args.getOrNull(1) as? Int) ?: 0)
        return when (val v = args.getOrNull(0)) {
            is String -> d.decode(v)
            is ByteArray -> d.decode(v)
            else -> UNKNOWN
        }
    }

    private fun encoder(flags: Int): java.util.Base64.Encoder {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e
    }

    private fun decoder(flags: Int): java.util.Base64.Decoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()

    companion object {
        private const val NO_PADDING = 1
        private const val URL_SAFE = 8
        private val FRAMEWORK_PREFIXES = listOf("Landroid/", "Lcom/android/", "Ldalvik/", "Llibcore/")
    }
}
