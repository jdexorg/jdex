package io.github.nitanmarcel.jdex.decompiler.cfg

object JdexCfgNormalize {
    fun foldGotoBlocks(cfg: JdexCfg, insns: Map<Int, CfgInsn>): JdexCfg {
        val blocks = LinkedHashMap<Int, JdexBlock>()
        for ((l, b) in cfg.blocks) {
            val nb = JdexBlock(l)
            nb.insnOffsets.addAll(b.insnOffsets)
            nb.successors.addAll(b.successors)
            nb.predecessors.addAll(b.predecessors)
            blocks[l] = nb
        }
        fun isLoneGoto(b: JdexBlock) =
            b.leader != cfg.entry && b.insnOffsets.size == 1 &&
                insns[b.insnOffsets.single()]?.kind == CfgInsnKind.GOTO && b.successors.size == 1

        var changed = true
        while (changed) {
            changed = false
            val g = blocks.values.firstOrNull { isLoneGoto(it) && it.predecessors.size == 1 } ?: break
            val target = g.successors.single()
            val pred = blocks.getValue(g.predecessors.single())
            if (target == g.leader) break
            pred.successors.remove(g.leader)
            pred.successors.add(target)
            blocks[target]?.let { it.predecessors.remove(g.leader); it.predecessors.add(pred.leader) }
            blocks.remove(g.leader)
            changed = true
        }
        return JdexCfg(cfg.entry, blocks)
    }
}
