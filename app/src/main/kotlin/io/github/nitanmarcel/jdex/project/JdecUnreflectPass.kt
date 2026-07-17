package io.github.nitanmarcel.jdex.project

import io.github.nitanmarcel.jdex.exec.analysis.DispatchTarget
import io.github.nitanmarcel.jdex.exec.analysis.ReflectInvokeTarget
import io.github.nitanmarcel.jdex.exec.analysis.UnreflectTarget
import io.github.nitanmarcel.jdex.exec.model.MethodRef
import jadx.api.plugins.input.insns.Opcode
import jadx.core.dex.info.FieldInfo
import jadx.core.dex.instructions.IndexInsnNode
import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.info.MethodInfo
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.InvokeNode
import jadx.core.dex.instructions.InvokeType
import jadx.core.dex.instructions.args.ArgType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.instructions.args.LiteralArg
import jadx.core.dex.instructions.args.RegisterArg
import jadx.core.dex.info.ClassInfo
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.utils.BlockUtils
import jadx.core.utils.InsnRemover

class JdecUnreflectPass(
    private val resolve: (dispatcherRaw: String, index: Int) -> UnreflectTarget?,
    private val resolveInvoke: (rawName: String, shortId: String, offset: Int) -> ReflectInvokeTarget? = { _, _, _ -> null },
    private val resolveDispatch: (dispatcherRaw: String, shortId: String, index: Int) -> DispatchTarget? = { _, _, _ -> null },
    private val resolveDispatchInvoke: (dispatcherRaw: String, shortId: String, index: Int, argCount: Int) -> ReflectInvokeTarget? = { _, _, _, _ -> null },
    private val isReflective: (dispatcherRaw: String, shortId: String) -> Boolean = { _, _ -> false },
    private val afterTypes: Boolean = false,
) : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo(if (afterTypes) "JdexUnreflectPost" else "JdexUnreflect", "rewrite reflection indirections")
            .after(if (afterTypes) "FinishTypeInference" else "JdexValueInject")
            .before(if (afterTypes) "ModVisitor" else "CodeShrinkVisitor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        for (block in mth.basicBlocks ?: return) {
            for (insn in block.instructions.toList()) {
                if (insn !is InvokeNode) continue
                if (afterTypes) {
                    if (insn.invokeType != InvokeType.STATIC || insn.argsCount == 0 || insn.result == null) continue
                    if (!isReflective(insn.callMth.declClass.fullName, insn.callMth.shortId)) continue
                    val index = constInt(insn.getArg(0)) ?: continue
                    val target = resolveDispatch(insn.callMth.declClass.fullName, insn.callMth.shortId, index) ?: continue
                    if (target.isField) rewriteFieldGet(mth, block, insn) else rewriteDispatchInvoke(mth, block, insn)
                } else {
                    if (isMethodInvoke(insn)) { rewriteInvoke(mth, block, insn); continue }
                    if (insn.invokeType == InvokeType.STATIC && insn.argsCount == 3 && isDispatcher(insn.callMth)) rewriteDispatcher(mth, block, insn)
                }
            }
        }
        if (afterTypes) dropRedundantCasts(mth)
    }

    private fun dropRedundantCasts(mth: MethodNode) {
        val root = mth.root()
        for (block in mth.basicBlocks ?: return) {
            for (insn in block.instructions.toList()) {
                if (insn !is IndexInsnNode || insn.type != InsnType.CHECK_CAST) continue
                val result = insn.result ?: continue
                if (result.sVar?.isTypeImmutable != true) continue
                val arg = insn.getArg(0)
                if (arg.isZeroLiteral) continue
                val castType = insn.index as? ArgType ?: continue
                if (ArgType.isCastNeeded(root, arg.type, castType)) continue
                val move = InsnNode(InsnType.MOVE, 1).apply {
                    setResult(result)
                    addArg(arg)
                    offset = insn.offset
                }
                BlockUtils.replaceInsn(mth, block, insn, move)
            }
        }
    }

    private fun rewriteDispatcher(mth: MethodNode, block: jadx.core.dex.nodes.BlockNode, insn: InvokeNode): Boolean {
        val index = constInt(insn.getArg(0)) ?: return false
        val elements = arrayElements(insn.getArg(2)) ?: return false
        val target = resolve(insn.callMth.declClass.fullName, index) ?: return false
        if (target.operandToElement.any { it !in elements.indices }) return false
        val mi = methodInfo(mth.root(), target.ref) ?: return false

        val newInv = InvokeNode(mi, invokeType(target.opcode), target.operandToElement.size)
        val recvOff = if (target.hasReceiver) 1 else 0
        val boxes = ArrayList<RegisterArg>()
        for (k in target.operandToElement.indices) {
            val raw = elements[target.operandToElement[k]]
            val pType = if (k < recvOff) null else target.ref.argTypes.getOrNull(k - recvOff)
            val unboxed = if (pType != null) unbox(raw, pType) else raw
            if (unboxed !== raw) (raw as? RegisterArg)?.let { boxes.add(it) }
            newInv.addArg(unboxed.duplicate())
        }
        val rt = insn.result?.let { ArgType.parse(target.ref.returnType) }
        insn.result?.let { r -> newInv.setResult(if (rt != null && !rt.isVoid) r.duplicate(rt) else r.duplicate()) }
        newInv.offset = insn.offset

        val arrayArg = insn.getArg(2) as? RegisterArg
        if (!BlockUtils.replaceInsn(mth, block, insn, newInv)) return false
        if (rt != null && !rt.isVoid) newInv.result?.let { pinResult(mth, it, rt) }
        if (target.hasReceiver) {
            val recvType = ArgType.`object`(dotted(target.ref.declClass))
            (newInv.getArg(0) as? RegisterArg)?.sVar?.markAsImmutable(recvType)
        }
        (insn.getArg(0) as? RegisterArg)?.let { removeIfDead(it) }
        arrayArg?.let { removeIfDead(it) }
        boxes.forEach { removeBoxIfDead(it) }
        return true
    }

    private fun rewriteDispatchInvoke(mth: MethodNode, block: jadx.core.dex.nodes.BlockNode, insn: InvokeNode) {
        val index = constInt(insn.getArg(0)) ?: return
        val elements = arrayElements(insn.getArg(2)) ?: return
        val target = resolveDispatchInvoke(insn.callMth.declClass.fullName, insn.callMth.shortId, index, elements.size) ?: return
        if (elements.isEmpty() && insn.result != null && ArgType.parse(target.ref.returnType).isPrimitive) return
        buildInvoke(mth, block, insn, target, elements)
    }

    private fun rewriteFieldGet(mth: MethodNode, block: jadx.core.dex.nodes.BlockNode, insn: InvokeNode) {
        val index = constInt(insn.getArg(0)) ?: return
        val target = resolveDispatch(insn.callMth.declClass.fullName, insn.callMth.shortId, index) ?: return
        if (!target.isField) return
        val res = insn.result ?: return
        val root = mth.root()
        val fieldType = ArgType.parse(target.memberType)
        val fi = FieldInfo.from(root, ClassInfo.fromType(root, ArgType.`object`(dotted(target.owner))), target.member, fieldType)

        val newInsn = if (target.isStatic) {
            IndexInsnNode(InsnType.SGET, fi, 0)
        } else {
            if (insn.argsCount < 2) return
            IndexInsnNode(InsnType.IGET, fi, 1).apply { addArg(insn.getArg(1).duplicate()) }
        }
        newInsn.setResult(res.duplicate(fieldType))
        newInsn.offset = insn.offset

        val indexArg = insn.getArg(0) as? RegisterArg
        if (!BlockUtils.replaceInsn(mth, block, insn, newInsn)) return
        newInsn.result?.let { pinResult(mth, it, fieldType) }
        if (!target.isStatic) (newInsn.getArg(0) as? RegisterArg)?.sVar?.markAsImmutable(ArgType.`object`(dotted(target.owner)))
        indexArg?.let { removeIfDead(it) }
    }

    private fun isMethodInvoke(insn: InvokeNode): Boolean =
        insn.argsCount == 3 && insn.callMth.name == "invoke" && insn.callMth.declClass.fullName == "java.lang.reflect.Method"

    private fun rewriteInvoke(mth: MethodNode, block: jadx.core.dex.nodes.BlockNode, insn: InvokeNode) {
        val rawName = mth.parentClass.classInfo.rawName
        val target = resolveInvoke(rawName, mth.methodInfo.shortId, insn.offset) ?: return
        val elements = arrayElements(insn.getArg(2)) ?: return
        buildInvoke(mth, block, insn, target, elements)
    }

    private fun buildInvoke(mth: MethodNode, block: jadx.core.dex.nodes.BlockNode, insn: InvokeNode, target: ReflectInvokeTarget, elements: List<InsnArg>) {
        if (elements.size != target.ref.argTypes.size) return
        val mi = methodInfo(mth.root(), target.ref) ?: return

        val itype = if (target.isStatic) InvokeType.STATIC else InvokeType.VIRTUAL
        val newInv = InvokeNode(mi, itype, elements.size + if (target.isStatic) 0 else 1)
        val boxes = ArrayList<RegisterArg>()
        if (!target.isStatic) newInv.addArg(insn.getArg(1).duplicate())
        for (k in target.ref.argTypes.indices) {
            val raw = elements[k]
            val unboxed = unbox(raw, target.ref.argTypes[k])
            if (unboxed !== raw) (raw as? RegisterArg)?.let { boxes.add(it) }
            newInv.addArg(unboxed.duplicate())
        }
        val rt = insn.result?.let { ArgType.parse(target.ref.returnType) }
        insn.result?.let { r -> newInv.setResult(if (rt != null && !rt.isVoid) r.duplicate(rt) else r.duplicate()) }
        newInv.offset = insn.offset

        val methodObj = insn.getArg(0) as? RegisterArg
        val arrayArg = insn.getArg(2) as? RegisterArg
        if (!BlockUtils.replaceInsn(mth, block, insn, newInv)) return
        if (rt != null && !rt.isVoid) newInv.result?.let { pinResult(mth, it, rt) }
        if (!target.isStatic) {
            (newInv.getArg(0) as? RegisterArg)?.sVar?.markAsImmutable(ArgType.`object`(dotted(target.ref.declClass)))
        }
        methodObj?.let { removeIfDead(it) }
        arrayArg?.let { removeIfDead(it) }
        boxes.forEach { removeBoxIfDead(it) }
    }

    private fun isDispatcher(m: MethodInfo): Boolean {
        val a = m.argumentsTypes
        return a.size == 3 && a[0] == ArgType.INT && a[1] == ArgType.OBJECT &&
            a[2].isArray && a[2].arrayElement == ArgType.OBJECT && m.returnType == ArgType.OBJECT
    }

    private fun invokeType(op: Opcode): InvokeType = when (op) {
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> InvokeType.STATIC
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> InvokeType.DIRECT
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> InvokeType.INTERFACE
        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> InvokeType.SUPER
        else -> InvokeType.VIRTUAL
    }

    private fun methodInfo(root: RootNode, ref: MethodRef): MethodInfo? {
        val cls = ClassInfo.fromType(root, ArgType.`object`(dotted(ref.declClass)))
        val args = ref.argTypes.map { ArgType.parse(it) }
        return MethodInfo.fromDetails(root, cls, ref.name, args, ArgType.parse(ref.returnType))
    }

    private fun dotted(desc: String) = desc.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun unbox(arg: InsnArg, paramType: String): InsnArg {
        val box = boxClass(paramType) ?: return arg
        val def = (arg as? RegisterArg)?.sVar?.assignInsn as? InvokeNode ?: return arg
        if (def.invokeType == InvokeType.STATIC && def.callMth.name == "valueOf" &&
            def.argsCount == 1 && def.callMth.declClass.fullName == box) {
            return def.getArg(0)
        }
        return arg
    }

    private fun boxClass(p: String): String? = when (p) {
        "I" -> "java.lang.Integer"; "J" -> "java.lang.Long"; "Z" -> "java.lang.Boolean"
        "B" -> "java.lang.Byte"; "C" -> "java.lang.Character"; "S" -> "java.lang.Short"
        "F" -> "java.lang.Float"; "D" -> "java.lang.Double"; else -> null
    }

    private fun constInt(arg: InsnArg): Int? {
        if (arg is LiteralArg) return arg.literal.toInt()
        val def = (arg as? RegisterArg)?.assignInsn ?: return null
        if (def.type != InsnType.CONST) return null
        return (def.getArg(0) as? LiteralArg)?.literal?.toInt()
    }

    private fun arrayElements(arg: InsnArg): List<InsnArg>? {
        val reg = arg as? RegisterArg ?: return null
        val def = reg.assignInsn ?: return null
        if (def.type != InsnType.NEW_ARRAY && def.type != InsnType.FILLED_NEW_ARRAY) return null
        if (def.type == InsnType.FILLED_NEW_ARRAY) return def.arguments.toList()
        val size = constInt(def.getArg(0))
        if (size == 0) return emptyList()
        val sv = reg.sVar ?: return null
        val byIndex = HashMap<Int, InsnArg>()
        for (use in sv.useList) {
            val u = use.parentInsn ?: continue
            if (u.type != InsnType.APUT) continue
            val i = constInt(u.getArg(1)) ?: return null
            byIndex[i] = u.getArg(2)
        }
        val n = size ?: ((byIndex.keys.maxOrNull() ?: return null) + 1)
        return (0 until n).map { byIndex[it] ?: return null }
    }

    private fun pinResult(mth: MethodNode, result: RegisterArg, rt: ArgType) {
        val collapsed = collapseBoxCast(mth, result, rt)
        val sv = result.sVar ?: return
        if (!rt.isPrimitive) sv.markAsImmutable(rt) else if (collapsed) sv.forceSetType(rt)
    }

    private fun collapseBoxCast(mth: MethodNode, res: RegisterArg, rt: ArgType): Boolean {
        val box = boxFqn(rt) ?: return false
        val sv = res.sVar ?: return false
        val cast = sv.useList.mapNotNull { it.parentInsn }.filter { !it.contains(AFlag.DONT_GENERATE) }.distinct().singleOrNull()
        if (cast == null || cast.type != InsnType.CHECK_CAST) return false
        val unbox = cast.result?.sVar?.useList?.mapNotNull { it.parentInsn }?.distinct()?.singleOrNull() as? InvokeNode ?: return false
        val unboxSv = unbox.result?.sVar ?: return false
        if (unbox.callMth.declClass.fullName != box || !unbox.callMth.name.endsWith("Value")) return false
        for (u in unboxSv.useList.toList()) u.parentInsn?.replaceArg(u, res.duplicate())
        InsnRemover.remove(mth, unbox)
        cast.add(AFlag.DONT_GENERATE)
        return true
    }

    private fun boxFqn(rt: ArgType): String? = when (rt) {
        ArgType.INT -> "java.lang.Integer"; ArgType.LONG -> "java.lang.Long"; ArgType.BOOLEAN -> "java.lang.Boolean"
        ArgType.BYTE -> "java.lang.Byte"; ArgType.CHAR -> "java.lang.Character"; ArgType.SHORT -> "java.lang.Short"
        ArgType.FLOAT -> "java.lang.Float"; ArgType.DOUBLE -> "java.lang.Double"; else -> null
    }

    private fun removeBoxIfDead(arg: RegisterArg) {
        val sv = arg.sVar ?: return
        if (sv.useList.any { it.parentInsn?.contains(AFlag.DONT_GENERATE) != true }) return
        sv.assignInsn?.add(AFlag.DONT_GENERATE)
    }

    private fun removeIfDead(arg: RegisterArg) {
        val sv = arg.sVar ?: return
        val def = sv.assignInsn ?: return
        if (def.type !in REMOVABLE) return
        val writes = sv.useList.map { it.parentInsn }
        if (writes.any { it == null || it.type !in WRITE_ONLY }) return
        def.add(AFlag.DONT_GENERATE)
        writes.forEach { it!!.add(AFlag.DONT_GENERATE) }
    }

    private companion object {
        val REMOVABLE = setOf(InsnType.CONST, InsnType.NEW_ARRAY, InsnType.FILLED_NEW_ARRAY)
        val WRITE_ONLY = setOf(InsnType.FILL_ARRAY, InsnType.APUT)
    }
}
