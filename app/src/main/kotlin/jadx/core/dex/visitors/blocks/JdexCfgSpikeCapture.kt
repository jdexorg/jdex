package jadx.core.dex.visitors.blocks

import io.github.nitanmarcel.jdex.decompiler.cfg.JadxCfgReference
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdexCfgSpikeCapture : JadxDecompilePass {
    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexCfgSpikeCapture", "spike: capture raw split CFG for parity")
            .after("BlockSplitter").before("BlockProcessor")

    override fun init(root: RootNode) {}
    override fun visit(cls: ClassNode): Boolean = true
    override fun visit(mth: MethodNode) {
        val s = sink ?: return
        val ref = JadxCfgReference.of(mth) ?: return
        s(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId, ref)
    }

    companion object {
        @Volatile @JvmStatic var sink: ((String, String, JadxCfgReference.Ref) -> Unit)? = null
    }
}
