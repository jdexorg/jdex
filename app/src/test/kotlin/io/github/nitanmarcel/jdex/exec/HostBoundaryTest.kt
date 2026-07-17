package io.github.nitanmarcel.jdex.exec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostBoundaryTest {
    private val h = HostBoundary()

    @Test fun allowList() {
        assertTrue(h.canHandle("Ljava/lang/String;"))
        assertTrue(h.canHandle("Ljava/lang/System;"))
        assertFalse(h.canHandle("Ljava/util/Random;"))
        assertFalse(h.canHandle("Ljava/util/Date;"))
    }

    @Test fun blocksNonDeterministicAndUnsafe() {
        assertTrue(h.isBlocked("Ljava/lang/System;", "currentTimeMillis"))
        assertTrue(h.isBlocked("Ljava/lang/System;", "nanoTime"))
        assertTrue(h.isBlocked("Ljava/lang/System;", "exit"))
        assertTrue(h.isBlocked("Ljava/lang/Math;", "random"))
        assertTrue(h.isBlocked("Ljava/lang/Object;", "hashCode"))
        assertFalse(h.isBlocked("Ljava/lang/Math;", "abs"))
        assertFalse(h.isBlocked("Ljava/lang/String;", "length"))
    }
}
