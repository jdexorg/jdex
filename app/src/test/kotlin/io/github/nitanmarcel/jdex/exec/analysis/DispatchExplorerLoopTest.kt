package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatchExplorerLoopTest {

    private val src = Fixtures.flattenedLoop()
    private val cls = "Lcom/jdex/cfgdemo/MainActivity;"

    @Test
    fun widensCounterLoopsToCompleteRecovery() {
        for (sig in listOf("collatzSteps(I)I", "fib(I)J", "processArray([I)I")) {
            val m = src.method(cls, sig)!!
            val g = DispatchExplorer(src).explore(m)
            assertNotNull(g) { "$sig must be detected as flattened" }
            assertTrue(g!!.complete) { "$sig must converge under widening" }
        }
    }

    @Test
    fun widenedEdgesCoverAllRealExecutionPaths() {
        val m = src.method(cls, "collatzSteps(I)I")!!
        val g = DispatchExplorer(src).explore(m)!!
        assertRecoveredEdgesCoverRealPaths(
            src, m, g,
            params = listOf(1, 2, 3, 6, 7, 27, 97, 1000, -5),
            minChecked = 50,
        )
    }
}
