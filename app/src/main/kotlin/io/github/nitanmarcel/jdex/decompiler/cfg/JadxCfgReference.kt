package io.github.nitanmarcel.jdex.decompiler.cfg

import jadx.core.dex.attributes.AType
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.MethodNode

object JadxCfgReference {
    data class Ref(val leaders: Set<Int>, val edges: Map<Int, Set<Int>>, val hasExceptionHandlers: Boolean)

    fun of(mth: MethodNode): Ref? {
        val blocks = mth.basicBlocks ?: return null
        if (blocks.isEmpty()) return null

        fun leaderOf(b: BlockNode): Int? = b.instructions.firstOrNull()?.offset

        fun realSuccessors(b: BlockNode, seen: MutableSet<BlockNode>): Set<Int> {
            val out = LinkedHashSet<Int>()
            for (s in b.successors) {
                if (!seen.add(s)) continue
                val l = leaderOf(s)
                if (l != null) out.add(l) else out.addAll(realSuccessors(s, seen))
            }
            return out
        }

        val leaders = LinkedHashSet<Int>()
        val edges = LinkedHashMap<Int, Set<Int>>()
        var hasExc = false
        for (b in blocks) {
            if (b.contains(AType.EXC_HANDLER)) hasExc = true
            val l = leaderOf(b) ?: continue
            leaders.add(l)
            edges[l] = realSuccessors(b, hashSetOf())
        }
        return Ref(leaders, edges, hasExc)
    }
}
