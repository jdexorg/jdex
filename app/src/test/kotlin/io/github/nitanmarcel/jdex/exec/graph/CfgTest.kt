package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfgTest {

    private val src = Fixtures.sample()

    private fun analyze(shortId: String, args: List<Any?>?) =
        Dataflow(Vm(src)).analyze(src.method("LSample;", shortId)!!, args)

    @Test
    fun concreteBranchPrunesToSingleSuccessor() {
        val m = src.method("LSample;", "pick(Z)I")!!
        val con = analyze("pick(Z)I", listOf(1))
        assertTrue(m.insns.all { con.successorsOf(it.offset).size <= 1 }) { "concrete branch should resolve to a line" }
    }

    @Test
    fun unknownBranchKeepsBothSuccessors() {
        val m = src.method("LSample;", "pick(Z)I")!!
        val unk = analyze("pick(Z)I", null)
        assertEquals(2, m.insns.maxOf { unk.successorsOf(it.offset).size })
    }

    @Test
    fun concreteSwitchResolvesToSelectedCase() {
        assertEquals(30, analyze("useSw()I", emptyList()).returnValue)
    }

    @Test
    fun unknownMultiwayKeepsAllSuccessors() {
        val m = src.method("LSample;", "sw(I)I")!!
        val unk = analyze("sw(I)I", null)
        assertTrue(m.insns.any { unk.successorsOf(it.offset).size >= 2 }) { "an unresolved multi-way should keep its branches" }
    }
}
