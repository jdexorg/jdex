package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatchExplorerTest {

    private val src = Fixtures.flattened()
    private val method = src.method("Lt/Flat;", "compute(I)I")!!

    @Test
    fun recoversFullyConnectedCfgFromFlattenedDispatch() {
        val g = DispatchExplorer(src).explore(method)
        assertNotNull(g)
        assertTrue(g!!.complete) { "exploration must finish within budget" }
        assertTrue(g.terminals.isNotEmpty()) { "must find a return" }

        val reached = HashSet<Int>().also { it.add(g.entry) }
        val stack = ArrayDeque<Int>().also { it.add(g.entry) }
        while (stack.isNotEmpty()) for (s in g.edges[stack.removeLast()].orEmpty()) if (reached.add(s)) stack.add(s)
        assertEquals(g.nodes, reached) { "every recovered block must be reachable from entry" }
        assertTrue(g.edges.values.any { it.size > 1 }) { "the param-conditional branches must be recovered" }
    }

    @Test
    fun recoveredEdgesCoverAllRealExecutionPaths() {
        val g = DispatchExplorer(src).explore(method)!!
        assertRecoveredEdgesCoverRealPaths(
            src, method, g,
            params = listOf(-1000, -100, -7, -3, -1, 0, 1, 2, 3, 7, 100, 1000),
            minChecked = 100,
        )
    }
}
