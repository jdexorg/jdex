package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.DispatchGraph
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.dex.visitors.blocks.BlockSplitter

class JdecDeflattenPass(private val graphFor: (rawName: String, shortId: String) -> DispatchGraph?) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexDeflatten", "rebuild flattened control flow").after("BlockSplitter").before("BlockProcessor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val g = graphFor(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId) ?: return
        if (!g.complete || g.strTarget.isEmpty()) return
        val blocks = mth.basicBlocks

        val ops = ArrayList<Redirect>(g.strTarget.size)
        for ((strOff, targetOff) in g.strTarget) {
            val block = blocks.firstOrNull { b -> b.instructions.any { it.offset == strOff } } ?: return
            val target = blocks.firstOrNull { it.startOffset == targetOff } ?: return
            if (block.successors.size != 1) return
            ops.add(Redirect(block, block.successors[0], target, strOff))
        }

        for (op in ops) {
            if (op.oldDst !== op.target) BlockSplitter.replaceConnection(op.block, op.oldDst, op.target)
            op.block.instructions.firstOrNull { it.offset == op.strOff }?.add(AFlag.DONT_GENERATE)
        }

        val reachable = HashSet<BlockNode>()
        val stack = ArrayDeque<BlockNode>().apply { add(mth.enterBlock); reachable.add(mth.enterBlock) }
        while (stack.isNotEmpty()) for (s in stack.removeLast().successors) if (reachable.add(s)) stack.add(s)
        for (b in ArrayList(mth.basicBlocks)) {
            if (b in reachable) continue
            for (s in ArrayList(b.successors)) BlockSplitter.removeConnection(b, s)
            for (p in ArrayList(b.predecessors)) BlockSplitter.removeConnection(p, b)
            b.add(AFlag.REMOVE)
        }
    }

    private class Redirect(val block: BlockNode, val oldDst: BlockNode, val target: BlockNode, val strOff: Int)
}
