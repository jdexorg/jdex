package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.model.DexMethod
import io.github.nitanmarcel.jdex.exec.graph.DataflowResult
import jadx.api.plugins.input.insns.Opcode

data class InsnAnnotation(val descriptor: String, val offset: Int, val text: String)

interface AnalysisPass {
    fun run(method: DexMethod, result: DataflowResult): List<InsnAnnotation>
}

internal fun usefulValue(v: Any?): Boolean = when (v) {
    is String, is Int, is Long, is Boolean, is Char, is Byte, is Short, is Float, is Double -> true
    else -> false
}

internal fun renderValue(v: Any?, type: String?): String = when {
    v is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    type == "Z" && v is Int -> (v != 0).toString()
    type == "F" && v is Int -> Float.fromBits(v).toString()
    type == "D" && v is Long -> Double.fromBits(v).toString()
    else -> v.toString()
}

internal val INVOKE_OPCODES = setOf(
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
)
