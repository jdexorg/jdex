package io.github.nitanmarcel.jdex.exec

import io.github.nitanmarcel.jdex.exec.model.MethodRef
import io.github.nitanmarcel.jdex.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostThreadSafetyTest {

    private val host = HostExec(HostBoundary())

    @Test
    fun threadInterruptIsNotHostExecuted() {
        Thread.interrupted()
        val ref = MethodRef("Ljava/lang/Thread;", "interrupt", emptyList(), "V")
        val r = host.invokeInstance(ref, Thread.currentThread(), emptyList())
        assertTrue(r is UnknownVal) { "Thread.interrupt must not be host-executed: $r" }
        assertFalse(Thread.currentThread().isInterrupted) { "the real decompile thread must not be interrupted" }
    }

    @Test
    fun threadSleepIsNotHostExecuted() {
        val ref = MethodRef("Ljava/lang/Thread;", "sleep", listOf("J"), "V")
        val t0 = System.nanoTime()
        val r = host.invokeStatic(ref, listOf(5000L))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r is UnknownVal) { "Thread.sleep must not be host-executed: $r" }
        assertTrue(elapsedMs < 1000) { "Thread.sleep must not really block: ${elapsedMs}ms" }
    }

    @Test
    fun threadReadMethodsStayAllowedForDecryptors() {
        val ref = MethodRef("Ljava/lang/Thread;", "currentThread", emptyList(), "Ljava/lang/Thread;")
        assertFalse(host.invokeStatic(ref, emptyList()) is UnknownVal) {
            "currentThread must stay host-executed (stack-trace-keyed decryptors need it)"
        }
    }
}
