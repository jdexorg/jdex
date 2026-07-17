package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.ReflectFieldSite
import io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeSite
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.info.ClassInfo
import jadx.core.dex.info.FieldInfo
import jadx.core.dex.info.MethodInfo
import jadx.core.dex.instructions.IndexInsnNode
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.InvokeNode
import jadx.core.dex.instructions.InvokeType
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode

class JdecReflectPreBlock(
    private val fields: (rawName: String, shortId: String) -> List<ReflectFieldSite>,
    private val invokes: (rawName: String, shortId: String) -> List<ReflectInvokeSite> = { _, _ -> emptyList() },
) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexReflectPreBlock", "rewrite reflective dispatchers before block-building")
            .before("BlockSplitter")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val fieldSites = fields(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId)
        val invokeSites = invokes(mth.parentClass.classInfo.rawName, mth.methodInfo.shortId)
        if (fieldSites.isEmpty() && invokeSites.isEmpty()) return
        val insns = mth.instructions ?: return
        val root = mth.root()
        val indexOfOffset = HashMap<Int, Int>()
        for (i in insns.indices) insns[i]?.let { indexOfOffset[it.offset] = i }

        for (site in fieldSites) {
            val idx = indexOfOffset[site.offset] ?: continue
            val inv = insns[idx] as? InvokeNode ?: continue
            val res = inv.result ?: continue
            val fieldType = ArgType.parse(site.memberType)
            val fi = FieldInfo.from(root, ClassInfo.fromType(root, ArgType.`object`(dotted(site.owner))), site.member, fieldType)
            val newInsn = if (site.isStatic) {
                IndexInsnNode(InsnType.SGET, fi, 0)
            } else {
                if (inv.argsCount < 2) continue
                IndexInsnNode(InsnType.IGET, fi, 1).apply { addArg(inv.getArg(1).duplicate()) }
            }
            newInsn.setResult(res.duplicate(fieldType))
            newInsn.offset = inv.offset
            insns[idx] = newInsn
            for (deadOff in site.deadOffsets) {
                val di = indexOfOffset[deadOff] ?: continue
                val dead = insns[di] ?: continue
                insns[di] = InsnNode(InsnType.NOP, 0).apply { offset = dead.offset }
            }
        }

        for (site in invokeSites) {
            val idx = indexOfOffset[site.offset] ?: continue
            val inv = insns[idx] as? InvokeNode ?: continue
            val ref = site.ref
            val cls = ClassInfo.fromType(root, ArgType.`object`(dotted(ref.declClass)))
            val argTypes = ref.argTypes.map { ArgType.parse(it) }
            val mi = MethodInfo.fromDetails(root, cls, ref.name, argTypes, ArgType.parse(ref.returnType))
            val itype = if (site.isStatic) InvokeType.STATIC else InvokeType.VIRTUAL
            val argc = site.operandRegs.size + if (site.isStatic) 0 else 1
            val newInv = InvokeNode(mi, itype, argc)
            if (!site.isStatic) {
                if (inv.argsCount < 2) continue
                newInv.addArg(inv.getArg(1).duplicate())
            }
            for (k in site.operandRegs.indices) newInv.addArg(InsnArg.reg(site.operandRegs[k], argTypes[k]))
            val rt = ArgType.parse(ref.returnType)
            inv.result?.let { newInv.setResult(if (rt.isVoid) it.duplicate() else it.duplicate(rt)) }
            newInv.offset = inv.offset
            insns[idx] = newInv
            for (deadOff in site.deadOffsets) {
                val di = indexOfOffset[deadOff] ?: continue
                val dead = insns[di] ?: continue
                insns[di] = InsnNode(InsnType.NOP, 0).apply { offset = dead.offset }
            }
        }
    }

    private fun dotted(desc: String) = desc.removePrefix("L").removeSuffix(";").replace('/', '.')
}
