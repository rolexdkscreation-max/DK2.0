package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test

/** 14 नवंबर 2026 (मालिक: Perplexity जैसा "Ask anything" डिब्बा + 3D लोगो + DK Computer की आँखें) */
class DkaiHomeCoreTest {
    @Test fun blueButtonVoiceOrSend() {
        assertEquals(DkaiHomeCore.InputAction.VOICE, DkaiHomeCore.inputAction(false, false, false))
        assertEquals(DkaiHomeCore.InputAction.SEND, DkaiHomeCore.inputAction(true, false, false))
        assertEquals(DkaiHomeCore.InputAction.SEND, DkaiHomeCore.inputAction(false, true, false))
        assertEquals(DkaiHomeCore.InputAction.SEND, DkaiHomeCore.inputAction(false, false, true))
    }

    @Test fun sendGoesToRightPlace() {
        assertEquals(DkaiHomeCore.SendRoute.CHAT, DkaiHomeCore.sendRoute(false, 0))
        assertEquals(DkaiHomeCore.SendRoute.COMPUTER, DkaiHomeCore.sendRoute(true, 0))
        // फोटो जुड़ी = हमेशा DK Computer (वही देख सकता है)
        assertEquals(DkaiHomeCore.SendRoute.COMPUTER_FILES, DkaiHomeCore.sendRoute(false, 3))
        assertEquals(DkaiHomeCore.SendRoute.COMPUTER_FILES, DkaiHomeCore.sendRoute(true, 1))
    }

    @Test fun fiveAtOnceNoDuplicates() {
        assertEquals(5, DkaiHomeCore.MAX_ATTACH)
        val (keep, over) = DkaiHomeCore.addUpTo(listOf("a", "b"), listOf("b", "c", "d", "e", "f", "g"))
        assertEquals(listOf("a", "b", "c", "d", "e"), keep)
        assertEquals(2, over)
        assertEquals(listOf("x") to 0, DkaiHomeCore.addUpTo(emptyList(), listOf("x", "x")))
    }

    @Test fun dictationJoins() {
        assertEquals("नमस्ते", DkaiHomeCore.joinDictation("", " नमस्ते "))
        assertEquals("मौसम कैसा है", DkaiHomeCore.joinDictation("मौसम ", "कैसा है"))
        assertEquals("पहले", DkaiHomeCore.joinDictation("पहले", ""))
    }

    @Test fun logoSpinsAndSways() {
        assertEquals(0f, Logo3DMath.sway(0f), 0.01f)
        assertEquals(Logo3DMath.SWAY_DEG, Logo3DMath.sway(0.25f), 0.01f)
        assertEquals(-Logo3DMath.SWAY_DEG, Logo3DMath.sway(0.75f), 0.01f)
        // आगे से = पीछे वाली परत पहले, पास वाली (0) आख़िर में; पीछे से उल्टा
        assertEquals(0, Logo3DMath.order(10f, 5).last())
        assertEquals(4, Logo3DMath.order(180f, 5).last())
        assertEquals(5, Logo3DMath.order(90f, 5).size)
    }

    @Test fun eyesMoveOnlyWhileWorking() {
        assertEquals(0f to 0f, EyesMath.look(0.4f, false))
        val pts = (0 until 20).map { EyesMath.look(it / 20f, true) }.toSet()
        assertTrue("काम करते समय आँखें घूमें", pts.size > 5)
        for ((x, y) in pts) { assertTrue(x in -1f..1f); assertTrue(y in -1f..1f) }
        assertEquals(1f, EyesMath.open(0.5f, true), 0.001f)
        assertTrue(EyesMath.open(0.30f, true) < 0.5f)          // पलक
        assertTrue(EyesMath.open(0.30f, true) > 0f)            // कभी पूरी गायब नहीं
    }
}
