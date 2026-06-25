package io.github.nitanmarcel.jdex.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CrossingStackTest {
    @Test fun startsInDexAtDepthZero() {
        val s = CrossingStack(Owner.DEX)
        assertEquals(Owner.DEX, s.owner); assertEquals(0, s.depth())
    }

    @Test fun crossPushesAndFlipsOwner() {
        val s = CrossingStack(Owner.DEX)
        s.cross(Owner.NATIVE, "step1")
        assertEquals(Owner.NATIVE, s.owner); assertEquals(1, s.depth())
        assertEquals("step1", s.peek()!!.resumeToken)
    }

    @Test fun reentrantNesting() {
        val s = CrossingStack(Owner.DEX)
        s.cross(Owner.NATIVE, "a")
        s.cross(Owner.DEX, "b")
        s.cross(Owner.NATIVE, "c")
        assertEquals(3, s.depth()); assertEquals(Owner.NATIVE, s.owner)
        assertEquals("c", s.back()!!.resumeToken); assertEquals(Owner.DEX, s.owner)
        assertEquals("b", s.back()!!.resumeToken); assertEquals(Owner.NATIVE, s.owner)
        assertEquals("a", s.back()!!.resumeToken); assertEquals(Owner.DEX, s.owner)
        assertNull(s.back()); assertEquals(Owner.DEX, s.owner)
    }
}
