package io.github.nitanmarcel.jdex.project

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.IndexInsnNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.instructions.args.InsnWrapArg
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdecConcatCastPass : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexConcatCast", "strip spurious primitive casts in string concat").after("PrepareForCodeGen")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        for (block in mth.basicBlocks ?: return) for (insn in block.instructions) strip(insn)
    }

    private fun strip(insn: InsnNode) {
        if (insn.type == InsnType.STR_CONCAT) {
            for (k in 0 until insn.argsCount) {
                val a = insn.getArg(k)
                val cast = (a as? InsnWrapArg)?.wrapInsn?.takeIf { it.type == InsnType.CAST } ?: continue
                if ((cast as? IndexInsnNode)?.indexAsType == ArgType.INT) insn.setArg(k, cast.getArg(0))
            }
        }
        for (a in insn.arguments) if (a is InsnWrapArg) strip(a.wrapInsn)
    }
}
