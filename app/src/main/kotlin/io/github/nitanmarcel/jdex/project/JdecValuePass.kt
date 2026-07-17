package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.InsnAnnotation
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType
import jadx.core.dex.instructions.ConstStringNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.instructions.args.RegisterArg
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.utils.BlockUtils

class JdecValuePass(private val forClass: (rawName: String) -> List<InsnAnnotation>) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexValueInject", "inject engine-resolved values").after("SSATransform").before("CodeShrinkVisitor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val shortId = mth.methodInfo.shortId
        val values = forClass(mth.parentClass.classInfo.rawName).asSequence()
            .filter { it.descriptor.substringAfter("->") == shortId }
            .mapNotNull { a ->
                val t = a.text
                if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                    a.offset to t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                } else null
            }.toMap()
        if (values.isEmpty()) return
        for (block in mth.basicBlocks ?: return) {
            for (insn in block.instructions.toList()) {
                if (insn.type != InsnType.INVOKE || insn.result == null) continue
                val str = values[insn.offset] ?: continue
                val deadArgs = insn.arguments.toList()
                val cs = ConstStringNode(str)
                cs.result = insn.result!!.duplicate(ArgType.STRING)
                cs.offset = insn.offset
                if (BlockUtils.replaceInsn(mth, insn, cs)) {
                    cs.remove(AType.METHOD_DETAILS)
                    collapseTrailingCast(mth, cs, str)
                }
                deadArgs.forEach { removeIfDead(it) }
            }
        }
    }

    private fun collapseTrailingCast(mth: MethodNode, cs: ConstStringNode, str: String) {
        val sv = cs.result?.sVar ?: return
        val cast = sv.useList.mapNotNull { it.parentInsn }.distinct().singleOrNull() ?: return
        if (cast.type != InsnType.CHECK_CAST || cast.result == null) return
        val cs2 = ConstStringNode(str)
        cs2.result = cast.result!!.duplicate(ArgType.STRING)
        cs2.offset = cast.offset
        if (BlockUtils.replaceInsn(mth, cast, cs2)) {
            cs2.remove(AType.METHOD_DETAILS)
            cs.add(AFlag.DONT_GENERATE)
        }
    }

    private fun removeIfDead(arg: InsnArg) {
        val reg = arg as? RegisterArg ?: return
        val sv = reg.sVar ?: return
        val def = sv.assignInsn ?: return
        if (def.type !in REMOVABLE) return
        val writes = sv.useList.map { it.parentInsn }
        if (writes.any { it == null || it.type !in WRITE_ONLY }) return
        def.add(AFlag.DONT_GENERATE)
        writes.forEach { it!!.add(AFlag.DONT_GENERATE) }
    }

    private companion object {
        val REMOVABLE = setOf(
            InsnType.CONST, InsnType.CONST_STR, InsnType.CONST_CLASS, InsnType.ARITH, InsnType.NEG,
            InsnType.NOT, InsnType.CAST, InsnType.MOVE, InsnType.FILLED_NEW_ARRAY, InsnType.NEW_ARRAY,
            InsnType.ARRAY_LENGTH, InsnType.CMP_L, InsnType.CMP_G, InsnType.INSTANCE_OF,
        )
        val WRITE_ONLY = setOf(InsnType.FILL_ARRAY, InsnType.FILL_ARRAY_DATA, InsnType.APUT)
    }
}
