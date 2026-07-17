package jadx.core.dex.visitors.blocks

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdexMultiEntryFix : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexMultiEntryFix", "reduce multi-entry loops via node splitting")
            .after("BlockProcessor").before("BlockFinisher")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val enter = mth.enterBlock ?: return
        if (mth.basicBlocks == null) return
        val irr = stronglyConnectedComponents(enter)
            .filter { it.size > 1 }
            .mapNotNull { l -> entriesOf(l, enter).let { e -> if (e.size >= 2) l to e else null } }
        if (irr.size != 1) return
        val (loop, entries) = irr[0]
        if (entries.size != 2) return
        if (loop.any { it.successors.size != it.cleanSuccessors.size }) return
        val h = entries.maxByOrNull { domainWeight(it, loop) } ?: return
        val domH = loop.filter { it === h || h.isDominator(it) }.toHashSet()
        val split = loop - domH
        if (split.isEmpty() || split.size > CAP) return
        try {
            applyTr(mth, domH, split)
            removeOrphans(mth)
            BlockProcessor.updateBlocksData(mth)
        } catch (e: Throwable) {
            return
        }
    }

    private fun applyTr(mth: MethodNode, domH: Set<BlockNode>, split: Set<BlockNode>) {
        val copy = HashMap<BlockNode, BlockNode>()
        for (s in split) {
            val c = BlockSplitter.startNewBlock(mth, s.startOffset)
            BlockSplitter.copyBlockData(s, c)
            c.add(AFlag.SYNTHETIC)
            copy[s] = c
        }
        for (s in split) {
            val c = copy.getValue(s)
            for (y in ArrayList(s.cleanSuccessors)) {
                val cy = copy[y]
                if (cy != null) {
                    BlockSplitter.connect(c, cy)
                    BlockSplitter.replaceTarget(c, y, cy)
                } else {
                    BlockSplitter.connect(c, y)
                }
            }
        }
        for (x in domH) {
            for (y in ArrayList(x.cleanSuccessors)) {
                val cy = copy[y] ?: continue
                BlockSplitter.replaceConnection(x, y, cy)
            }
        }
    }

    private fun removeOrphans(mth: MethodNode) {
        val enter = mth.enterBlock
        var removed = true
        while (removed) {
            removed = false
            for (b in ArrayList(mth.basicBlocks)) {
                if (b !== enter && b.predecessors.isEmpty()) {
                    BlockProcessor.removeUnreachableBlock(b, mth)
                    removed = true
                }
            }
        }
    }

    private fun domainWeight(h: BlockNode, loop: Set<BlockNode>): Int =
        loop.filter { it === h || h.isDominator(it) }.sumOf { it.instructions.size + 1 }

    private fun entriesOf(loop: Set<BlockNode>, enter: BlockNode): Set<BlockNode> {
        val entries = HashSet<BlockNode>()
        for (n in loop) {
            if (n === enter) { entries.add(n); continue }
            if (n.predecessors.any { it !in loop && n in it.cleanSuccessors }) entries.add(n)
        }
        return entries
    }

    private fun stronglyConnectedComponents(enter: BlockNode): List<Set<BlockNode>> {
        val index = HashMap<BlockNode, Int>()
        val low = HashMap<BlockNode, Int>()
        val onStack = HashSet<BlockNode>()
        val comp = ArrayDeque<BlockNode>()
        val work = ArrayDeque<Pair<BlockNode, Iterator<BlockNode>>>()
        val out = ArrayList<Set<BlockNode>>()
        var idx = 0
        index[enter] = idx; low[enter] = idx; idx++
        comp.addLast(enter); onStack.add(enter)
        work.addLast(enter to enter.cleanSuccessors.iterator())
        while (work.isNotEmpty()) {
            val (v, it) = work.last()
            if (it.hasNext()) {
                val w = it.next()
                val wi = index[w]
                if (wi == null) {
                    index[w] = idx; low[w] = idx; idx++
                    comp.addLast(w); onStack.add(w)
                    work.addLast(w to w.cleanSuccessors.iterator())
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), wi)
                }
            } else {
                work.removeLast()
                work.lastOrNull()?.let { (p, _) -> low[p] = minOf(low.getValue(p), low.getValue(v)) }
                if (low.getValue(v) == index.getValue(v)) {
                    val scc = HashSet<BlockNode>()
                    while (true) {
                        val x = comp.removeLast(); onStack.remove(x); scc.add(x)
                        if (x === v) break
                    }
                    out.add(scc)
                }
            }
        }
        return out
    }

    private companion object {
        const val CAP = 30
    }
}
