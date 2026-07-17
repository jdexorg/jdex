package io.github.nitanmarcel.jdex.exec.analysis

import io.github.nitanmarcel.jdex.exec.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatchExplorerIntTest {

    private val src = Fixtures.dispatch()

    @Test
    fun selfContainedIntegerFlatteningIsRecoveredAndDeflattenable() {
        val m = src.method("LIntFlat;", "run()Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertNotNull(g) { "integer-XOR flattening must be recognized, not just String.hashCode()" }
        assertTrue(g!!.complete) { "self-contained integer dispatch resolves fully" }
        assertTrue(g.strTarget.isNotEmpty()) { "concrete state->target mappings must be recovered for the rewrite" }

        val reached = HashSet<Int>().also { it.add(g.entry) }
        val stack = ArrayDeque<Int>().also { it.add(g.entry) }
        while (stack.isNotEmpty()) for (s in g.edges[stack.removeLast()].orEmpty()) if (reached.add(s)) stack.add(s)
        assertEquals(g.nodes, reached) { "every recovered node reachable from entry" }
    }

    @Test
    fun parameterIndexedDispatcherIsNotDeflattened() {
        val m = src.method("LIntFlat;", "pidx(I)Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertTrue(g == null || g.strTarget.isEmpty()) {
            "a dispatcher keyed on the call argument (xor v, p0) is not a closed constant system; " +
                "deflatten must skip it rather than linearize a runtime-dependent switch"
        }
    }

    @Test
    fun additiveConstClosedStateMachineIsRecoveredAndDeflattenable() {
        val m = src.method("LAddFlat;", "run()Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertNotNull(g) { "an additive const-closed state machine must be recognized, not only XOR" }
        assertTrue(g!!.complete)
        assertTrue(g.strTarget.isNotEmpty()) { "concrete state->target mappings must be recovered for the rewrite" }
    }

    @Test
    fun argKeyedAdditiveDispatcherIsNotDeflattened() {
        val m = src.method("LAddFlat;", "pidx(I)Ljava/lang/String;")!!
        val g = DispatchExplorer(src).explore(m)
        assertTrue(g == null || g.strTarget.isEmpty()) {
            "the state is derived from the call argument, so it is not a closed constant system and must not be deflattened"
        }
    }
}
