package io.github.nitanmarcel.jdex.disasm

import io.github.nitanmarcel.jdex.exec.AndroidStubs
import io.github.nitanmarcel.jdex.exec.EmuWorld
import io.github.nitanmarcel.jdex.exec.ExecLimits
import io.github.nitanmarcel.jdex.exec.HookCall
import io.github.nitanmarcel.jdex.exec.MethodSource
import io.github.nitanmarcel.jdex.exec.NotHandled
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.constructObject
import io.github.nitanmarcel.jdex.exec.model.MethodRef

class VmJavaBridge(
    private val source: MethodSource,
    private val limits: ExecLimits = ExecLimits(maxMillis = 2000),
    private val world: EmuWorld? = null,
) : JavaBridge {

    override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
        val classDesc = "L${className.replace('.', '/')};"
        val shortId = methodName + signature
        if (methodName == "<init>") {
            val w = world ?: return null
            return runCatching { constructObject(source, w, classDesc, signature, args) }.getOrNull()
        }
        var callArgs = args
        world?.hooks?.interceptors("$classDesc->$shortId")?.takeIf { it.isNotEmpty() }?.let { list ->
            val call = HookCall("$classDesc->$shortId", receiver, args.toMutableList())
            for (h in list) h.onInvoke(call)
            if (call.replaced) return call.result
            callArgs = call.args
        }
        val method = source.method(classDesc, shortId)
        if (method != null) {
            if (!method.isStatic && receiver == null) return null
            return runCatching {
                vm().invoke(method, callArgs, if (method.isStatic) null else receiver)
            }.getOrNull()
        }
        val stubs = world?.android ?: return null
        val ref = MethodRef(classDesc, methodName, emptyList(), signature.substringAfterLast(')'))
        return runCatching { stubs.callStatic(ref, callArgs) }.getOrNull()?.takeIf { it !== NotHandled }
    }

    private fun vm(): Vm = Vm(
        source, limits = limits,
        hooks = world?.hooks, ctx = world?.ctx,
        android = world?.android ?: AndroidStubs(),
        statics = world?.statics ?: HashMap(),
    )
}
