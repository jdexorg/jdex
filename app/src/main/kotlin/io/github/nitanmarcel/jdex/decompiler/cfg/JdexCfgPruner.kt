package io.github.nitanmarcel.jdex.decompiler.cfg

object JdexCfgPruner {
    fun prune(cfg: JdexCfg, decidedBranches: Map<Int, Int>, deadOffsets: Set<Int>): JdexCfg {
        val blocks = LinkedHashMap<Int, JdexBlock>()
        for ((leader, src) in cfg.blocks) {
            if (leader in deadOffsets) continue
            val nb = JdexBlock(leader)
            nb.insnOffsets.addAll(src.insnOffsets)
            blocks[leader] = nb
        }
        for ((leader, nb) in blocks) {
            val src = cfg.blocks.getValue(leader)
            val lastOff = src.insnOffsets.last()
            val decided = decidedBranches[lastOff]
            val keep = if (decided != null) {
                val tl = blocks.keys.filter { it <= decided }.maxOrNull()
                if (tl != null && tl in blocks) setOf(tl) else src.successors
            } else src.successors
            keep.filter { it in blocks }.forEach { s ->
                nb.successors.add(s)
                blocks.getValue(s).predecessors.add(leader)
            }
        }
        val live = JdexCfg(cfg.entry, blocks)
        val reach = live.reachable()
        val kept = LinkedHashMap<Int, JdexBlock>()
        for ((leader, b) in blocks) if (leader in reach) {
            b.predecessors.retainAll(reach)
            kept[leader] = b
        }
        return JdexCfg(cfg.entry, kept)
    }
}
