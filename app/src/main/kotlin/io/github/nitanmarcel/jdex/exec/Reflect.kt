package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.model.FieldRef
import io.github.nitanmarcel.jdex.exec.model.MethodRef

data class DvmClass(val desc: String)

data class DvmMethodHandle(val dexMethod: DexMethod?, val hostMethod: java.lang.reflect.Method?, val symbol: MethodRef? = null)

data class DvmField(val ref: FieldRef, val isStatic: Boolean)
