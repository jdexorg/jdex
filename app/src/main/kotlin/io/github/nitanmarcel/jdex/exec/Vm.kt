package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.runtime.ClinitState
import io.github.nitanmarcel.jdex.exec.runtime.UNKNOWN
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal

class VmAbort(message: String) : RuntimeException(message)

interface NativeBridge {
    fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any? = null): Any?
}

class Vm(
    val source: MethodSource,
    val host: HostBoundary = HostBoundary(),
    val limits: ExecLimits = ExecLimits(),
    val hook: ExecHook? = null,
    val nativeBridge: NativeBridge? = null,
    val hooks: HookRegistry? = null,
    val ctx: EngineContext? = null,
    val android: AndroidStubs = AndroidStubs(),
    val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    val androidEnvUnknown: Boolean = true,
) {
    val hostExec = HostExec(host, android)
    private val clinitState = HashMap<String, ClinitState>()
    private val interp = Interpreter(this)
    private var depth = 0
    private var deadline = 0L

    fun invoke(method: DexMethod, args: List<Any?> = emptyList(), receiver: Any? = null): Any? {
        deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        runCatching { ensureClinit(method.declClass) }
        deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        return runCatching { interp.run(method, args, receiver) }.getOrElse { UNKNOWN }
    }

    fun call(method: DexMethod, args: List<Any?>, receiver: Any?): Any? {
        if (depth >= limits.maxDepth) return UnknownVal(method.ref.returnType)
        depth++
        try {
            return interp.run(method, args, receiver)
        } catch (e: VmAbort) {
            return UnknownVal(method.ref.returnType)
        } finally {
            depth--
        }
    }

    fun staticsOf(declClass: String): HashMap<String, Any?> = statics.getOrPut(declClass) { HashMap() }

    fun initialized(): Set<String> = clinitState.keys

    fun hostStaticField(declClass: String, name: String): Any? {
        if (androidEnvUnknown && android.env.field(declClass, name) !== NotHandled) return NotHandled
        val stub = android.field(declClass, name)
        if (stub !== NotHandled) return stub
        if (!host.canHandle(declClass)) return NotHandled
        return runCatching {
            Class.forName(declClass.removePrefix("L").removeSuffix(";").replace('/', '.')).getField(name).get(null)
        }.getOrDefault(NotHandled)
    }

    fun ensureClinit(desc: String) {
        if (deadline == 0L) deadline = if (limits.maxMillis <= 0) Long.MAX_VALUE else System.nanoTime() + limits.maxMillis * 1_000_000
        if (clinitState[desc] != null) return
        val info = source.classInfo(desc) ?: return
        clinitState[desc] = ClinitState.RUNNING
        info.superType?.let { ensureClinit(it) }
        val snap = ctx?.clinitStaticsFor(desc)
        if (snap != null) {
            staticsOf(desc).putAll(snap)
            clinitState[desc] = ClinitState.DONE
            return
        }
        if (info.staticInits.isNotEmpty()) staticsOf(desc).putAll(info.staticInits)
        source.method(desc, "<clinit>()V")?.let { call(it, emptyList(), null) }
        clinitState[desc] = ClinitState.DONE
    }

    fun deadlineExceeded(): Boolean = System.nanoTime() >= deadline
}
