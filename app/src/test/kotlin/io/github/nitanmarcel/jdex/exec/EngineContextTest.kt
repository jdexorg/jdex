package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.graph.Dataflow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineContextTest {

    @Test
    fun clinitBackedValueIsIdenticalWithSharedContext() {
        val src = Fixtures.sample()
        val m = src.method("LSample;", "useAdd()I")!!
        val bare = Dataflow(Vm(src)).analyze(m).returnValue
        val ctx = EngineContext(src)
        val shared = Dataflow(Vm(src, ctx = ctx)).analyze(m).returnValue
        assertEquals(bare, shared)
    }

    @Test
    fun deepCopyIsolatesMutableStatics() {
        val src = Fixtures.sample()
        val ctx = EngineContext(src)
        val a = Vm(src, ctx = ctx); a.ensureClinit("LSample;")
        val b = Vm(src, ctx = ctx); b.ensureClinit("LSample;")
        val sa = a.staticsOf("LSample;"); val sb = b.staticsOf("LSample;")
        var checked = 0
        for ((k, v) in sa) if (v is ByteArray || v is Array<*>) {
            assertTrue(sb[k] !== v) { "shared mutable static $k must be a distinct deep copy" }
            checked++
        }
        assertTrue(checked > 0) { "expected mutable-array statics in LSample;: ${sa.keys}" }
    }
}
