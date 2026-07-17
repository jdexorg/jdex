package io.github.nitanmarcel.jdex.project

import jadx.api.plugins.pass.JadxPassInfo
import jadx.api.plugins.pass.impl.OrderedJadxPassInfo
import jadx.api.plugins.pass.types.JadxDecompilePass
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.instructions.ArithNode
import jadx.core.dex.instructions.ArithOp
import jadx.core.dex.instructions.IfNode
import jadx.core.dex.instructions.IfOp
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.args.InsnArg
import jadx.core.dex.instructions.args.InsnWrapArg
import jadx.core.dex.instructions.args.LiteralArg
import jadx.core.dex.instructions.args.RegisterArg
import jadx.core.dex.nodes.BlockNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.InsnNode
import jadx.core.dex.nodes.MethodNode
import jadx.core.dex.nodes.RootNode
import jadx.core.dex.visitors.blocks.BlockProcessor
import jadx.core.dex.visitors.blocks.BlockSplitter
import jadx.core.utils.BlockUtils
import jadx.core.utils.InsnRemover

class JdecBlockLockPass : JadxDecompilePass {
    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexBlockLock", "keep block lists mutable").after("BlockSplitter").before("BlockFinisher")

    override fun init(root: RootNode) {}
    override fun visit(cls: ClassNode): Boolean = true
    override fun visit(mth: MethodNode) { mth.add(AFlag.DISABLE_BLOCKS_LOCK) }
}

class JdecConstFoldPass : JadxDecompilePass {

    override fun getInfo(): JadxPassInfo =
        OrderedJadxPassInfo("JdexConstFold", "fold constant opaque predicates").after("SSATransform").before("ConstInlineVisitor")

    override fun init(root: RootNode) {}

    override fun visit(cls: ClassNode): Boolean = true

    override fun visit(mth: MethodNode) {
        val blocks = mth.basicBlocks ?: return
        var folded = false
        for (block in ArrayList(blocks)) {
            val last = BlockUtils.getLastInsn(block) as? IfNode ?: continue
            val a = evalArg(last.getArg(0), 0) ?: continue
            val b = evalArg(last.getArg(1), 0) ?: continue
            val taken = decide(last.op, a, b)
            val notTaken = if (taken) last.elseBlock else last.thenBlock
            val takenBlock = if (taken) last.thenBlock else last.elseBlock

            folded = true
            val ops = listOf(last.getArg(0), last.getArg(1))
            InsnRemover.remove(mth, block, last)
            if (notTaken != null && notTaken !== takenBlock) BlockSplitter.removeConnection(block, notTaken)
            block.updateCleanSuccessors()
            cascade(mth, ops)
        }
        if (folded) {
            for (b in ArrayList(mth.basicBlocks)) {
                if (b !== mth.enterBlock && b.predecessors.isEmpty()) BlockProcessor.removeUnreachableBlock(b, mth)
            }
            BlockProcessor.updateBlocksData(mth)
        }
    }

    private fun decide(op: IfOp, a: Int, b: Int): Boolean = when (op) {
        IfOp.EQ -> a == b; IfOp.NE -> a != b; IfOp.LT -> a < b
        IfOp.LE -> a <= b; IfOp.GT -> a > b; IfOp.GE -> a >= b
    }

    private fun evalArg(arg: InsnArg, depth: Int): Int? {
        if (depth > 32) return null
        return when (arg) {
            is LiteralArg -> arg.literal.toInt()
            is RegisterArg -> arg.sVar?.assignInsn?.let { evalInsn(it, depth) }
            is InsnWrapArg -> evalInsn(arg.wrapInsn, depth)
            else -> null
        }
    }

    private fun evalInsn(insn: InsnNode, depth: Int): Int? = when (insn.type) {
        InsnType.CONST -> (insn.getArg(0) as? LiteralArg)?.literal?.toInt()
        InsnType.MOVE -> evalArg(insn.getArg(0), depth + 1)
        InsnType.ARITH -> {
            val a = evalArg(insn.getArg(0), depth + 1)
            val b = evalArg(insn.getArg(1), depth + 1)
            if (a == null || b == null) null else applyArith((insn as ArithNode).op, a, b)
        }
        else -> null
    }

    private fun applyArith(op: ArithOp, a: Int, b: Int): Int? = when (op) {
        ArithOp.ADD -> a + b; ArithOp.SUB -> a - b; ArithOp.MUL -> a * b
        ArithOp.DIV -> if (b == 0) null else a / b; ArithOp.REM -> if (b == 0) null else a % b
        ArithOp.AND -> a and b; ArithOp.OR -> a or b; ArithOp.XOR -> a xor b
        ArithOp.SHL -> a shl (b and 0x1f); ArithOp.SHR -> a shr (b and 0x1f); ArithOp.USHR -> a ushr (b and 0x1f)
    }

    private fun cascade(mth: MethodNode, seed: List<InsnArg>) {
        val wl = ArrayDeque(seed.filterIsInstance<RegisterArg>())
        while (wl.isNotEmpty()) {
            val sv = wl.removeFirst().sVar ?: continue
            if (sv.useCount != 0) continue
            val def = sv.assignInsn ?: continue
            if (def.type !in CASCADE_PURE) continue
            val block = BlockUtils.getBlockByInsn(mth, def) ?: continue
            val inputs = (0 until def.argsCount).map { def.getArg(it) }
            InsnRemover.remove(mth, block, def)
            wl.addAll(inputs.filterIsInstance<RegisterArg>())
        }
    }

    private companion object {
        val CASCADE_PURE = setOf(InsnType.CONST, InsnType.CONST_STR, InsnType.CONST_CLASS, InsnType.ARITH, InsnType.NEG, InsnType.NOT, InsnType.MOVE, InsnType.CAST)
    }
}
