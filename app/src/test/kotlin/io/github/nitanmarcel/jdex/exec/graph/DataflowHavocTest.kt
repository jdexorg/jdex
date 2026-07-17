package io.github.nitanmarcel.jdex.exec.graph

import io.github.nitanmarcel.jdex.exec.Fixtures
import io.github.nitanmarcel.jdex.exec.Vm
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataflowHavocTest {

    @Test
    fun havocsObjectFieldsAcrossUnmodeledCall() {
        val src = Fixtures.opaque()
        val r = Dataflow(Vm(src)).analyze(src.method("LOpaque;", "leak()I")!!)
        assertTrue(r.returnValue is UnknownVal) {
            "b.x was passed to an unmodeled call that could mutate it, so the later read must be Unknown, got: ${r.returnValue}"
        }
    }
}
