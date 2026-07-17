package io.github.nitanmarcel.jdex.decompiler.cfg

enum class CfgInsnKind { NORMAL, IF, GOTO, SWITCH, RETURN, THROW, SEPARATOR, MOVE_RESULT }

data class CfgInsn(val offset: Int, val kind: CfgInsnKind, val targets: List<Int>)

class TryRegion(val start: Int, val end: Int, val handlers: List<Int>)

class JdexBlock(val leader: Int) {
    val insnOffsets = mutableListOf<Int>()
    val successors = LinkedHashSet<Int>()
    val predecessors = LinkedHashSet<Int>()
}

class JdexCfg(val entry: Int, val blocks: LinkedHashMap<Int, JdexBlock>)

fun JdexCfg.reachable(): Set<Int> {
    val seen = LinkedHashSet<Int>()
    val stack = ArrayDeque<Int>()
    stack.addLast(entry)
    while (stack.isNotEmpty()) {
        val l = stack.removeLast()
        if (!seen.add(l)) continue
        blocks[l]?.successors?.forEach { stack.addLast(it) }
    }
    return seen
}

fun JdexCfg.unreachable(): Set<Int> = blocks.keys - reachable()
