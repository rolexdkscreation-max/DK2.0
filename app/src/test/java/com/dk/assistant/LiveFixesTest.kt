package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.39: लाइव के सुधार - 1007, caption, VAD, पीछे की आवाज़, गूँज, Bluetooth, काम की सच्ची हालत */
class LiveFixesTest {

    @After fun tearDown() { TaskStatus.clear(); TaskStatus.clock = { System.currentTimeMillis() } }

    // ------------------------------------------------------------------ 1) 1007: क्या भेजा + फ़ैसला
    @Test fun sentTrace_kindsOnly_noUserText_audioCollapsed() {
        var t = 1_000L
        val w = SentTrace(clock = { t })
        w.sent("""{"setup":{"model":"models/x"}}""")
        repeat(25) { t += 40; w.sent("""{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=16000","data":"AAAA"}}}""") }
        w.sent("""{"realtimeInput":{"audioStreamEnd":true}}""")
        w.sent("""{"realtimeInput":{"text":"मेरा गुप्त सवाल"}}""")
        w.sent("""{"toolResponse":{"functionResponses":[{"id":"fc-9","name":"coding_task","response":{"result":"ok"}}]}}""")
        t += 200
        val s = w.summary()
        assertTrue(s, s.contains("setup") && s.contains("audio×25") && s.contains("audioStreamEnd") && s.contains("text"))
        assertTrue(s, s.contains("toolResponse(coding_task id=fc-9)"))
        assertFalse("आपकी बात का text लॉग में नहीं जाता", s.contains("गुप्त"))
    }

    @Test fun drop1007_freshSessionAlways_searchOffOnSecondWithin2Min() {
        val d = Drop1007()
        val a = d.hit(0, searchOn = true)
        assertTrue(a.freshSession); assertFalse(a.disableSearch)
        assertTrue(d.hit(30_000, searchOn = true).disableSearch)
        assertFalse(d.hit(60_000, searchOn = false).disableSearch)                // पहले से बंद
        val d2 = Drop1007(); d2.hit(0, true)
        assertFalse(d2.hit(200_000, true).disableSearch)                          // 2 मिनट बाद = नई गिनती
    }

    // ------------------------------------------------------------------ 3) "बात खत्म" एक बार, असली बोलने पर
    @Test fun streamEndGate_oncePerUtterance_notOnShortNoise() {
        val g = StreamEndGate()
        assertFalse(g.onSpeechEnd(speechMs = 160, hasWords = false))            // खटखट
        assertTrue(g.onSpeechEnd(speechMs = 1200, hasWords = false))
        assertFalse(g.onSpeechEnd(speechMs = 1200, hasWords = true))            // दोबारा नहीं (पहले 4 बार जाता था)
        assertFalse(g.onSilenceEnd())                                            // 1s चुप्पी वाला भी नहीं
        g.onReal()
        assertTrue(g.onSpeechEnd(speechMs = 200, hasWords = true))              // छोटी पर असली शब्द
        g.onReal()
        assertTrue(g.onSilenceEnd())
    }

    @Test fun vadLimits_longerPauseAllowed() {
        assertEquals(550, GeminiLive.CLIENT_END_MS)
        assertEquals(800, GeminiLive.SERVER_SILENCE_MS)   // v3.46: फ़ोन 550ms पर खुद भेजता है; सर्वर सिर्फ़ अधूरे वाक्य पर
    }

    // ------------------------------------------------------------------ 5) पीछे की आवाज़
    @Test fun duckWatch_releasesAfter3sQuiet_onlyWhenAllQuiet() {
        assertTrue(DuckWatch.shouldRelease(true, false, false, false, quietSince = 1_000, now = 4_000))
        assertFalse(DuckWatch.shouldRelease(true, false, false, false, 1_000, 3_500))     // अभी 2.5s
        assertFalse(DuckWatch.shouldRelease(true, true, false, false, 1_000, 9_000))      // यूज़र बोल रहा
        assertFalse(DuckWatch.shouldRelease(true, false, true, false, 1_000, 9_000))      // DK बोल रहा
        assertFalse(DuckWatch.shouldRelease(true, false, false, true, 1_000, 9_000))      // जवाब बन रहा
        assertFalse(DuckWatch.shouldRelease(false, false, false, false, 1_000, 9_000))    // धीमी है ही नहीं
    }

    // ------------------------------------------------------------------ 2) caption: टूटने से पहले नया
    @Test fun captionRotate_at50s_waitsWhileUserSpeaks_until58s() {
        assertFalse(CaptionRotate.now(ageMs = 40_000, lastRealAudioAgoMs = 10_000))
        assertTrue(CaptionRotate.now(50_000, 5_000))
        assertFalse(CaptionRotate.now(52_000, 300))                               // यूज़र बोल रहा - रुको
        assertTrue(CaptionRotate.now(58_000, 300))                                // 61s से पहले बदलना ही है
    }

    // ------------------------------------------------------------------ Bluetooth
    @Test fun btPick_scoFirst_thenBle_elseNone() {
        val speaker = 2; val builtinMic = 15; val wired = 3
        assertEquals(1, BtPick.pick(listOf(speaker, BtPick.TYPE_BLUETOOTH_SCO, BtPick.TYPE_BLE_HEADSET)))
        assertEquals(2, BtPick.pick(listOf(speaker, builtinMic, BtPick.TYPE_BLE_HEADSET)))
        assertEquals(-1, BtPick.pick(listOf(speaker, builtinMic, wired)))         // Bluetooth नहीं = speaker वाला तरीका
    }

    // ------------------------------------------------------------------ 7) गूँज: DK की अपनी आवाज़ से तुलना
    private fun feedRun(b: BargeDetector, from: Long, n: Int, rms: Double, out: Double): Boolean {
        var hit = false
        for (i in 0 until n) if (b.feed(rms, from + i * 40L, out)) hit = true
        return hit
    }

    @Test fun barge_loudDkEcho_isNotUser_butRealUserIs() {
        // DK धीमे से शुरू (गूँज 600), फिर ज़ोर से बोला (गूँज 1800) - पहले यह "यूज़र" लगता था
        val old = BargeDetector().apply { ambient = 200.0 }
        feedRun(old, 1_000, 10, 600.0, 0.0)
        assertTrue("पुराना तरीका: तेज़ गूँज = गलत अलार्म", feedRun(old, 1_400, 10, 1800.0, 0.0))

        val b = BargeDetector().apply { ambient = 200.0 }
        feedRun(b, 1_000, 10, 600.0, 2000.0)                                          // सीखा: गूँज = DK की आवाज़ का ~0.3
        assertFalse("DK ज़ोर से बोला - वही गूँज, यूज़र नहीं", feedRun(b, 1_400, 10, 1800.0, 6000.0))
        assertTrue("यूज़र सच में बोला", feedRun(b, 1_800, 8, 9000.0, 2000.0))
    }

    // ------------------------------------------------------------------ 6) काम की सच्ची हालत
    @Test fun coder_understandingWaitsForYes_isWaitingNotFinished() {
        val understood = "✍ मैंने आपकी बात ऐसे समझी:\nBluetooth माइक\n\nक्या मैंने सही समझा? \"हाँ\" लिखें/बोलें तो काम शुरू होगा।"
        assertEquals(TaskStatus.State.WAITING, TaskStatus.coderState("d", understood, false))
        assertEquals(TaskStatus.State.DONE, TaskStatus.coderState("d", "📋 रिपोर्ट\n✅ काम पूरा हुआ", false))     // पुराना नियम वैसा ही
    }

    @Test fun waitingJob_staysActive_noteAndSummarySayNotDone() {
        var now = 0L; TaskStatus.clock = { now }
        val notes = mutableListOf<TaskStatus.Job>()
        val l = TaskStatus.Listener { j, ended -> if (ended) notes.add(j) }
        TaskStatus.listen(l)
        try {
            TaskStatus.start(TaskStatus.CODER, "कोडिंग टीम", "the coding team", "Confirm and ensure the coding team makes the APK ready")
            now = 30_000
            TaskStatus.end(TaskStatus.CODER, TaskStatus.State.WAITING, "✍ मैंने आपकी बात ऐसे समझी: Bluetooth माइक")
            val j = TaskStatus.get(TaskStatus.CODER)!!
            assertTrue(j.active)
            val note = TaskStatus.endNote(notes.single())
            assertTrue(note, note.contains("NOT done") && note.contains("WAITING") && note.contains("do not say any APK is ready"))
            assertFalse(note.contains("has now really ended"))
            val s = TaskStatus.summary()
            assertTrue(s, s.startsWith("STATE: WAITING FOR THE USER") && s.contains("NOT DONE YET"))
            assertTrue(s, s.contains("an instruction, NOT a status"))
            assertTrue(s, s.contains("Never say a PR is merged or an APK is ready"))
        } finally { TaskStatus.unlisten(l) }
    }
}
