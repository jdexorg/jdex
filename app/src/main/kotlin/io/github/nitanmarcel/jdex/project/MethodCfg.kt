package io.github.nitanmarcel.jdex.project

class MethodCfg(val shortId: String, val blocks: List<CfgBlock>, val edges: List<CfgEdge>)

class CfgInsn(val offset: Int, val addr: Int, val line: Int?, val text: String)

class CfgBlock(val id: Int, val startOffset: Int, val insns: List<CfgInsn>)

enum class CfgEdgeKind { FALLTHROUGH, COND_TRUE, COND_FALSE, GOTO, SWITCH_CASE, EXCEPTION }

data class CfgEdge(val from: Int, val to: Int, val kind: CfgEdgeKind, val label: String? = null)
