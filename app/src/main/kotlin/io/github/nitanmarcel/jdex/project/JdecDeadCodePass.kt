package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.MethodCfgFacts
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.utils.InsnRemover

class JdecDeadCodePass(private val factsFor: (rawName: String, shortId: String) -> MethodCfgFacts?) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexDeadCode", "remove dead opaque-predicate computations").after("SSATransform").before("CodeShrinkVisitor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val facts = factsFor(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId) ?: return
        val folded = facts.foldedReads
        val foldedCalls = facts.foldedCalls
        var changed = true
        while (changed) {
            changed = false
            for (block in ArrayList(mth.basicBlocks)) {
                for (insn in ArrayList(block.instructions)) {
                    val removable = insn.type in PURE ||
                        (insn.type in FOLDED_READS && insn.offset in folded) ||
                        (insn.type == InsnType.INVOKE && insn.offset in foldedCalls)
                    if (!removable) continue
                    val sv = insn.result?.sVar ?: continue
                    if (sv.useCount == 0) { InsnRemover.remove(mth, block, insn); changed = true }
                }
            }
        }
    }

    private companion object {
        val PURE = setOf(
            InsnType.CONST, InsnType.CONST_STR, InsnType.CONST_CLASS, InsnType.ARITH, InsnType.NEG,
            InsnType.NOT, InsnType.CAST, InsnType.MOVE, InsnType.CMP_L, InsnType.CMP_G,
            InsnType.ARRAY_LENGTH, InsnType.INSTANCE_OF,
        )
        val FOLDED_READS = setOf(InsnType.AGET, InsnType.SGET, InsnType.IGET)
    }
}
