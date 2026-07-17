package io.github.nitanmarcel.jdex.exec.model

sealed interface InsnRef

data class MethodRef(
    val declClass: String,
    val name: String,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef {
    val shortId: String = buildString {
        append(name); append('(')
        argTypes.forEach { append(it) }
        append(')'); append(returnType)
    }
}

data class FieldRef(val declClass: String, val name: String, val type: String) : InsnRef {
    val key: String = "$declClass.$name"
}

data class TypeRef(val desc: String) : InsnRef

data class StringRef(val value: String) : InsnRef

data class CallSiteRef(
    val name: String,
    val recipe: String?,
    val constants: List<Any?>,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef
