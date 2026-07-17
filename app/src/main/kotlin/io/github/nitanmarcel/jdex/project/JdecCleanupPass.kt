package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.MethodCfgFacts
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.IfNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.dex.visitors.blocks.BlockSplitter
import jadx.core.utils.BlockUtils

class JdecCleanupPass(private val factsFor: (rawName: String, shortId: String) -> MethodCfgFacts?) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexFoldDeadBranches", "drop statically-decided branches")
            .after("BlockSplitter").before("BlockProcessor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val f = factsFor(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId) ?: return
        if (f.decidedBranches.isEmpty()) return
        for (block in ArrayList(mth.basicBlocks)) {
            val last = BlockUtils.getLastInsn(block) ?: continue
            val taken = f.decidedBranches[last.offset] ?: continue
            when (last.type) {
                InsnType.IF -> {
                    val ifn = last as IfNode
                    val then = ifn.thenBlock
                    val els = ifn.elseBlock
                    val takenBlock = when (taken) {
                        firstOffset(then) -> then
                        firstOffset(els) -> els
                        else -> continue
                    }
                    val notTaken = if (takenBlock === then) els else then
                    block.instructions.removeAt(block.instructions.size - 1)
                    if (notTaken != null && notTaken !== takenBlock) BlockSplitter.removeConnection(block, notTaken)
                    block.updateCleanSuccessors()
                }
                InsnType.SWITCH -> {
                    val live = block.successors.firstOrNull { firstOffset(it) == taken } ?: continue
                    block.instructions.removeAt(block.instructions.size - 1)
                    for (succ in ArrayList(block.successors)) if (succ !== live) BlockSplitter.removeConnection(block, succ)
                    block.updateCleanSuccessors()
                }
                else -> continue
            }
        }
    }

    private fun firstOffset(b: BlockNode?): Int = b?.let { BlockUtils.getFirstInsn(it)?.offset } ?: -1
}
