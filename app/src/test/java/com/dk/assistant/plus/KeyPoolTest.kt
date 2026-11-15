package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {
    private val a = KeyPool.Entry("a", "https://x", "k1", "m")
    private val b = KeyPool.Entry("b", "https://x", "k2", "m")
    private val c = KeyPool.Entry("c", "https://x", "k3", "m")

    @Test fun roundRobin() {
        val p = KeyPool.Pool(listOf(a, b, c), useAll = true)
        assertEquals(a, p.order(0).first()); assertEquals(b, p.order(0).first()); assertEquals(c, p.order(0).first()); assertEquals(a, p.order(0).first())
    }

    @Test fun limit_movesToNextKey_andCools() {
        val p = KeyPool.Pool(listOf(a, b), useAll = true, coolMs = 1000)
        val tried = mutableListOf<String>()
        val r = p.run({ 0L }) { e -> tried += e.name; if (e == a) KeyPool.Res.Limit("429") else KeyPool.Res.Ok(e.name) }
        assertEquals(KeyPool.Res.Ok("b"), r); assertEquals(listOf("a", "b"), tried)
        // a ठंडी - अगली बार (round-robin से a की बारी) भी a आख़िर में
        assertEquals(b, p.order(10).first())
    }

    @Test fun useAllOff_onlyFirstKey() {
        val p = KeyPool.Pool(listOf(a, b, c), useAll = false)
        assertEquals(1, p.size); assertEquals(listOf(a), p.order(0)); assertEquals(listOf(a), p.order(0))
    }

    @Test fun unusableSkipped_andFatalStops() {
        val bad = KeyPool.Entry("bad", "http://x", "k", "m")
        val p = KeyPool.Pool(listOf(bad, a, b), useAll = true)
        assertEquals(2, p.size)
        var n = 0
        assertTrue(p.run({ 0L }) { n++; KeyPool.Res.Fatal("x") } is KeyPool.Res.Fatal); assertEquals(1, n)
        assertEquals("limit", KeyPool.classify(429, "")); assertEquals("ok", KeyPool.classify(200, "")); assertEquals("fatal", KeyPool.classify(400, "bad"))
    }
}
