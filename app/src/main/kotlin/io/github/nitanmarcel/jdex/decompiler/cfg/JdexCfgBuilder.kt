package io.github.nitanmarcel.jdex.decompiler.cfg

object JdexCfgBuilder {
    fun build(insns: List<CfgInsn>, tries: List<TryRegion> = emptyList()): JdexCfg {
        require(insns.isNotEmpty())
        val byOffset = insns.associateBy { it.offset }
        val ordered = insns.sortedBy { it.offset }
        val nextOf = HashMap<Int, Int?>()
        for (i in ordered.indices) nextOf[ordered[i].offset] = ordered.getOrNull(i + 1)?.offset

        val leaders = sortedSetOf(ordered.first().offset)
        for (ins in ordered) {
            if (ins.kind != CfgInsnKind.NORMAL && ins.kind != CfgInsnKind.GOTO && ins.kind != CfgInsnKind.MOVE_RESULT) leaders.add(ins.offset)
            when (ins.kind) {
                CfgInsnKind.IF, CfgInsnKind.SWITCH -> {
                    ins.targets.forEach { leaders.add(it) }
                    nextOf[ins.offset]?.let { leaders.add(it) }
                }
                CfgInsnKind.GOTO -> {
                    ins.targets.forEach { leaders.add(it) }
                    nextOf[ins.offset]?.let { leaders.add(it) }
                }
                CfgInsnKind.RETURN, CfgInsnKind.THROW, CfgInsnKind.SEPARATOR -> nextOf[ins.offset]?.let { leaders.add(it) }
                CfgInsnKind.NORMAL, CfgInsnKind.MOVE_RESULT -> {}
            }
        }
        val offsets = ordered.map { it.offset }
        for (t in tries) {
            offsets.firstOrNull { it >= t.start }?.let { leaders.add(it) }
            offsets.firstOrNull { it >= t.end }?.let { leaders.add(it) }
            t.handlers.forEach { h -> offsets.firstOrNull { it >= h }?.let { leaders.add(it) } }
        }
        leaders.removeAll { byOffset[it]?.kind == CfgInsnKind.MOVE_RESULT }

        val blocks = LinkedHashMap<Int, JdexBlock>()
        var cur: JdexBlock? = null
        for (ins in ordered) {
            if (ins.offset in leaders) { cur = JdexBlock(ins.offset); blocks[ins.offset] = cur }
            cur!!.insnOffsets.add(ins.offset)
        }

        fun leaderContaining(off: Int): Int = blocks.keys.filter { it <= off }.max()
        fun addEdge(from: JdexBlock, toLeader: Int) {
            from.successors.add(toLeader); blocks.getValue(toLeader).predecessors.add(from.leader)
        }
        for (b in blocks.values) {
            val last = byOffset.getValue(b.insnOffsets.last())
            val succs = when (last.kind) {
                CfgInsnKind.IF, CfgInsnKind.SWITCH -> last.targets + listOfNotNull(nextOf[last.offset])
                CfgInsnKind.GOTO -> last.targets
                CfgInsnKind.RETURN, CfgInsnKind.THROW -> emptyList()
                CfgInsnKind.NORMAL, CfgInsnKind.SEPARATOR, CfgInsnKind.MOVE_RESULT -> listOfNotNull(nextOf[last.offset])
            }
            succs.forEach { addEdge(b, leaderContaining(it)) }
        }
        for (t in tries) {
            val handlerLeaders = t.handlers.mapNotNull { h -> offsets.firstOrNull { it >= h }?.let { leaderContaining(it) } }
            for (b in blocks.values) if (b.leader >= t.start && b.leader < t.end) {
                handlerLeaders.forEach { addEdge(b, it) }
            }
        }
        return JdexCfg(ordered.first().offset, blocks)
    }
}
