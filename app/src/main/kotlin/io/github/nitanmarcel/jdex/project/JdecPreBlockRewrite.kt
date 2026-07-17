package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.StringSite
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.instructions.ConstStringNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdecPreBlockRewrite(
    private val sites: (rawName: String, shortId: String) -> List<StringSite>,
) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexPreBlockRewrite", "rewrite raw instructions before block-building")
            .before("BlockSplitter")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val list = sites(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId)
        if (list.isEmpty()) return
        val insns = mth.instructions ?: return
        val indexOfOffset = HashMap<Int, Int>()
        for (i in insns.indices) insns[i]?.let { indexOfOffset[it.offset] = i }
        for (site in list) {
            val invIdx = indexOfOffset[site.offset] ?: continue
            val inv = insns[invIdx] ?: continue
            if (inv.type != InsnType.INVOKE) continue
            val resultReg = inv.result ?: continue
            insns[invIdx] = ConstStringNode(site.value).apply { setResult(resultReg.duplicate()); offset = inv.offset }
            for (deadOff in site.deadOffsets) {
                val di = indexOfOffset[deadOff] ?: continue
                val dead = insns[di] ?: continue
                insns[di] = InsnNode(InsnType.NOP, 0).apply { offset = dead.offset }
            }
        }
    }
}
