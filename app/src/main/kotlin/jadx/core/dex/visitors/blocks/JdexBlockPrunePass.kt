package jadx.core.dex.visitors.blocks

import io.github.nitanmarcel.jdex.exec.analysis.MethodCfgFacts
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.TargetInsnNode
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.utils.BlockUtils

class JdexBlockPrunePass(private val factsFor: (String, String) -> MethodCfgFacts?) : JadxDecompilePass {
    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexBlockPrune", "drop unreachable/proven-dead blocks so BlockProcessor can't abort")
            .after("BlockSplitter").before("BlockProcessor")

    override fun init(root: RootNode) {}
    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val enter = mth.enterBlock ?: return
        mth.basicBlocks ?: return
        val dead = factsFor(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId)?.deadOffsets ?: emptySet()

        val removed = HashSet<BlockNode>()
        var changed = true
        while (changed) {
            changed = false
            val reachable = HashSet<BlockNode>()
            val stack = ArrayDeque<BlockNode>()
            stack.addLast(enter)
            while (stack.isNotEmpty()) {
                val b = stack.removeLast()
                if (reachable.add(b)) b.successors.forEach { stack.addLast(it) }
            }
            val toRemove = mth.basicBlocks.filter { b ->
                b != enter && (b !in reachable || (b.instructions.firstOrNull()?.offset ?: -1) in dead)
            }
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { BlockSplitter.detachBlock(it) }
                mth.basicBlocks.removeAll(toRemove.toSet())
                removed.addAll(toRemove)
                changed = true
            }
        }
        if (removed.isEmpty()) return
        for (b in mth.basicBlocks) {
            val last = BlockUtils.getLastInsn(b) as? TargetInsnNode ?: continue
            val fallback = b.successors.firstOrNull() ?: continue
            for (r in removed) last.replaceTargetBlock(r, fallback)
        }
        mth.updateBlockPositions()
    }
}
