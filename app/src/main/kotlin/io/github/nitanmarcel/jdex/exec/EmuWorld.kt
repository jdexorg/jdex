package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.runtime.DvmObject

class EmuWorld(
    val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    val android: AndroidStubs = AndroidStubs(),
    val hooks: HookRegistry = HookRegistry(),
    val ctx: EngineContext? = null,
)

fun worldVm(src: MethodSource, world: EmuWorld, limits: ExecLimits = ExecLimits()): Vm =
    Vm(src, limits = limits, hooks = world.hooks, ctx = world.ctx, android = world.android, statics = world.statics, androidEnvUnknown = false)

fun constructObject(src: MethodSource, world: EmuWorld, classDesc: String, ctorSig: String?, args: List<Any?>): DvmObject {
    val obj = DvmObject(classDesc)
    val sig = ctorSig ?: "()V"
    val init = src.method(classDesc, "<init>$sig") ?: throw IllegalArgumentException("no <init>$sig on $classDesc")
    worldVm(src, world).invoke(init, args, obj)
    return obj
}
