package io.github.nitanmarcel.jdex.exec.model

class Handler(val type: String?, val offset: Int)

class TryBlock(val start: Int, val end: Int, val handlers: List<Handler>)

class DexMethod(
    val declClass: String,
    val ref: MethodRef,
    val isStatic: Boolean,
    val registersCount: Int,
    val paramWords: Int,
    val insns: List<DalvikInsn>,
    val offsetToIndex: Map<Int, Int>,
    val tries: List<TryBlock>,
    val codeOffset: Int,
)
