package io.github.nitanmarcel.jdex.disasm

object Mnemonics {
    private val CALL = setOf("bl", "blr", "blx", "blraa", "blrab", "call", "lcall", "jal", "jalr", "jalx", "bal", "balc")
    private val RET = setOf("ret", "retn", "retf", "return", "eret", "retaa", "retab", "iret", "iretd", "iretq")
    private val pc = Regex("\\bpc\\b")

    fun isCall(mnem: String): Boolean = mnem.lowercase() in CALL

    fun isReturn(mnem: String, ops: String): Boolean {
        val m = mnem.lowercase()
        val o = ops.trim().lowercase()
        return when {
            m in RET -> true
            m == "bx" && o == "lr" -> true
            m == "mov" && o.replace(" ", "") == "pc,lr" -> true
            (m.startsWith("pop") || m.startsWith("ldm")) && pc.containsMatchIn(o) -> true
            (m == "jr" || m.startsWith("jr.")) && o == "\$ra" -> true
            else -> false
        }
    }
}
