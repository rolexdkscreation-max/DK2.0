package com.dk.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** openWakeWord streaming - नकली मॉडल से: 80 ms पर 8 mel frame, 76 frame की खिड़की, 16 embedding के बाद ही score */
class OwwStreamTest {
    private var melIn = 0
    private val s = OwwStream(
        mel = { x -> melIn = x.size; Array(8) { FloatArray(32) { 0f } } },
        emb = { win -> assertEquals(76, win.size); FloatArray(96) { win.last()[0] } },
        heads = mapOf("hey_dk" to { f -> assertEquals(16, f.size); 0.9f }, "hello_dk" to { _ -> 0.1f }),
    )

    @Test fun scoresOnlyAfter16Steps() {
        val chunk = ShortArray(WakeRules.STEP)
        repeat(15) { assertTrue(s.feed(chunk).isEmpty()) }
        assertEquals(WakeRules.STEP + WakeRules.MEL_CTX, melIn)
        val r = s.feed(chunk)
        assertEquals(0.9f, r["hey_dk"]!!, 1e-6f)
        assertEquals(0.1f, r["hello_dk"]!!, 1e-6f)
    }

    @Test fun melScaledLikePython_andResetClears() {
        val chunk = ShortArray(WakeRules.STEP)
        repeat(20) { s.feed(chunk) }
        s.reset()
        assertEquals(0, s.realFeats)
        assertTrue(s.feed(chunk).isEmpty())
    }

    @Test fun noScoreWhenAsked() {
        val chunk = ShortArray(WakeRules.STEP)
        repeat(30) { assertTrue(s.feed(chunk, score = false).isEmpty()) }
    }
}
