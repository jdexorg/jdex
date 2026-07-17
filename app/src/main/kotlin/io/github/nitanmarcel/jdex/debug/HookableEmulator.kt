package io.github.nitanmarcel.jdex.debug

import io.github.nitanmarcel.jdex.exec.Interceptor

interface HookableEmulator {
    fun addHook(descriptor: String, hook: Interceptor): Int
    fun removeHook(id: Int): Boolean
}
