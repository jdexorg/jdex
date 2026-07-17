package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatchExplorerCfTest {

    private val src = Fixtures.dispatch()

    @Test
    fun flatteningInsideCatchHandlerIsRecovered() {
        val m = src.method("LCatchFlat;", "run(Ljava/lang/String;)Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertNotNull(g)
        assertTrue(g!!.complete) { "catch-block dispatch resolves fully once the handler is seeded" }
        assertTrue(g.strTarget.isNotEmpty()) { "the catch-block state->target mappings must be recovered" }
    }

    @Test
    fun frameworkThrowInDispatcherDoesNotAbortDeflatten() {
        val m = src.method("LDispatchThrow;", "run()Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertNotNull(g)
        assertTrue(g!!.complete) {
            "an emulated framework throw (SystemClock.uptimeMillis -> StubNotImplemented) on a dispatch path " +
                "must be absorbed as Unknown, not degrade the whole graph to incomplete"
        }

        val reached = HashSet<Int>().also { it.add(g.entry) }
        val stack = ArrayDeque<Int>().also { it.add(g.entry) }
        while (stack.isNotEmpty()) for (s in g.edges[stack.removeLast()].orEmpty()) if (reached.add(s)) stack.add(s)
        assertEquals(g.nodes, reached) { "every recovered node must be reachable from entry" }
        assertTrue(g.terminals.isNotEmpty()) { "the return past the throwing case must be recovered" }
    }
}
