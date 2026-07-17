package io.github.nitanmarcel.jdex.project

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdecTypePinPass(
    private val typesFor: (rawName: String, shortId: String) -> Map<Int, ArgType>?,
) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexTypePin", "rescue engine-inferred types on inference failures")
            .after("FixTypesVisitor").before("FinishTypeInference")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val types = typesFor(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId) ?: return
        if (types.isEmpty()) return
        for (sv in mth.sVars) {
            if (sv.isTypeImmutable || sv.typeInfo.type.isTypeKnown) continue
            val off = sv.assignInsn?.offset ?: continue
            val at = types[off] ?: continue
            sv.forceSetType(at)
        }
    }
}
