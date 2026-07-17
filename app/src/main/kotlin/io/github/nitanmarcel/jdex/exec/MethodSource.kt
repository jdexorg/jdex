package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.DexMethod

data class FieldMeta(val ref: io.github.nitanmarcel.jdex.exec.model.FieldRef, val isStatic: Boolean)

data class ClassInfo(
    val type: String,
    val superType: String?,
    val interfaces: List<String>,
    val isInterface: Boolean,
    val staticInits: Map<String, Any?> = emptyMap(),
    val fields: List<FieldMeta> = emptyList(),
)

interface MethodSource {
    fun method(classDesc: String, shortId: String): DexMethod?
    fun classInfo(classDesc: String): ClassInfo?
    fun methodsByName(classDesc: String, name: String): List<DexMethod>
    fun methodsOf(classDesc: String): List<DexMethod>

    fun allMethods(): List<DexMethod>

    fun isNative(classDesc: String, shortId: String): Boolean = false
}
