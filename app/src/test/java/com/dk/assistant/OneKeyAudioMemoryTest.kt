package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.6 (owner के नियम): एक key = सिर्फ़ बातचीत/चैट/खोज/फ़ोन के काम; Bluetooth पर आवाज़ सच में headset में;
 * speaker पर DK तेज़; घंटी हमेशा वापस; धीमी आवाज़ भी सुने; "सुना पर किया नहीं" = फ़ोन खुद करे; हाल की बातें याद.
 */
class OneKeyAudioMemoryTest {

    // ------------------------------------------------------------------ एक key
    @Test fun oneKeyBlocksOnlyHeavyAgents() {
        for (w in OneKeyGate.Work.values()) {
            assertTrue(OneKeyGate.blocked(true, w))
            assertFalse(OneKeyGate.blocked(false, w))
        }
        assertFalse(OneKeyGate.blocked(true, null))                     // बात/चैट/खोज/फ़ोन के काम = रोक नहीं
        assertEquals(LiveAgentRoute.TOOLS, OneKeyGate.LIVE_TOOLS.keys)  // चारों एजेंट tool, न कम न ज़्यादा
        assertFalse(OneKeyGate.LIVE_TOOLS.containsKey("phone_task"))
        assertFalse(OneKeyGate.LIVE_TOOLS.containsKey("web_search"))
        assertFalse(OneKeyGate.LIVE_TOOLS.containsKey("whatsapp_task"))
    }

    @Test fun oneKeyMessageTellsHowToSwitch() {
        val m = OneKeyGate.message(OneKeyGate.Work.CODING)
        assertTrue(m.contains("कोडिंग एजेंट") && m.contains("एक key से सब") && m.contains("बंद"))
        val n = OneKeyGate.liveNote(OneKeyGate.Work.STORY)
        assertTrue(n.contains("Do NOT say it is done") && n.contains("not_started"))
        assertFalse(LiveAgentRoute.started(n))                          // एजेंट की स्क्रीन पर "काम मिला" नहीं दिखेगा
    }

    @Test fun featuresSayOneKeyIsOnlyForTalk() {
        val f = LiveConnect.features(autoOn = false, autoKinds = emptyList(), oneKey = true)
        assertTrue(f.contains("one all-rounder key") && f.contains("ONLY for talking"))
        assertFalse(LiveConnect.features(autoOn = false, autoKinds = emptyList(), oneKey = false).contains("ONLY for talking"))
    }

    @Test fun agentEntryPointsAreGated() {
        assertTrue(src("GeminiLive.kt").contains("OneKeyGate.LIVE_TOOLS[name]"))
        assertTrue(src("ComputerAgent.kt").contains("OneKeyGate.check(ctx, OneKeyGate.Work.COMPUTER)"))
        assertTrue(src("CoderTeam.kt").contains("OneKeyGate.check(c, OneKeyGate.Work.CODING)"))
        assertTrue(src("SevenRunner.kt").contains("OneKeyGate.check(c, OneKeyGate.Work.CODING)"))
        assertTrue(src("CodingActivity.kt").contains("OneKeyGate.check(this, OneKeyGate.Work.CODING)"))
        assertTrue(src("LiveReport.kt").contains("OneKeyGate.check(c, OneKeyGate.Work.STORY)"))
        assertTrue(src("StoryWriterActivity.kt").contains("OneKeyGate.check(this, OneKeyGate.Work.STORY)"))
        assertTrue(src("forge/ForgeController.kt").contains("OneKeyGate.check(c, OneKeyGate.Work.WEBSITE)"))
        assertTrue(src("BrainManager.kt").contains("ONE KEY MODE"))
    }

    @Test fun noKeyOpensKeyScreenFirst() {
        val m = src("MainActivity.kt")
        val key = m.indexOf("!Prefs.hasAnyKey(this) ->"); val mic = m.indexOf("!Setup.hasMic(this) -> { askPerms()")
        assertTrue(key in 0 until mic)                                  // key पहले, माइक की अनुमति बाद में
        assertTrue(m.contains("SettingsActivity.EXTRA_FOCUS_KEY, true"))
        assertTrue(src("SettingsActivity.kt").contains("intent.getBooleanExtra(EXTRA_FOCUS_KEY, false)"))
    }

    // ------------------------------------------------------------------ Bluetooth
    @Test fun bluetoothRouteMustReallyBeHeadset() {
        assertTrue(BtPick.routedToHeadset(BtPick.TYPE_BLUETOOTH_SCO))
        assertTrue(BtPick.routedToHeadset(BtPick.TYPE_BLE_HEADSET))
        assertFalse(BtPick.routedToHeadset(1))       // TYPE_BUILTIN_EARPIECE = ऊपर वाला छोटा कॉल-स्पीकर
        assertFalse(BtPick.routedToHeadset(2))       // TYPE_BUILTIN_SPEAKER
        assertFalse(BtPick.routedToHeadset(null))
        assertTrue(BtPick.scoConnected(1)); assertFalse(BtPick.scoConnected(0)); assertFalse(BtPick.scoConnected(2))
        assertTrue(BtPick.VERIFY_AT_MS.size >= 2 && BtPick.VERIFY_AT_MS[0] < BtPick.VERIFY_AT_MS[1])
        val r = src("AudioRoute.kt")
        assertTrue(r.contains("verifyRoute()") && r.contains("refused") && r.contains("it(false)"))
    }

    // ------------------------------------------------------------------ आवाज़
    @Test fun speakerLouderBluetoothUntouched() {
        assertEquals(0, SpeakerBoost.gainFor(true))
        assertTrue(SpeakerBoost.gainFor(false) in 300..1000)
        assertTrue(SpeakerBoost.SUSPECT_VOLUME > 0.3f && SpeakerBoost.SUSPECT_VOLUME < 1f)
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("SpeakerBoost.attach(t.audioSessionId, btOn)") && g.contains("SpeakerBoost.release(spkBoost)"))
        assertFalse(g.contains("setVolume(0.3f)"))
    }

    @Test fun duckOnlyWhenMusicAndShorter() {
        assertFalse(DuckRule.shouldDuck(false))
        assertTrue(DuckRule.shouldDuck(true))
        assertTrue(DuckRule.MAX_HOLD_MS < 90_000L)
        assertTrue(src("Ducker.kt").contains("DuckRule.shouldDuck(musicOn)"))
    }

    @Test fun ringerAlwaysComesBack() {
        assertTrue(RingGuard.shouldRestore(callActive = false))
        assertFalse(RingGuard.shouldRestore(callActive = true))
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("getSharedPreferences(\"dk_ring\"") && cm.contains("RingGuard.shouldRestore"))
    }

    // ------------------------------------------------------------------ सुनना
    @Test fun quieterSpeechOpensGate() {
        assertTrue(LiveHear.START_FACTOR < 2.5 && LiveHear.START_MIN < 300.0 && LiveHear.LOUD_FACTOR <= LiveHear.START_FACTOR)
        fun starts(d: TurnDetector): Boolean {
            var t = 0L
            repeat(50) { d.feed(250.0, t); t += 40 }
            var s = false
            repeat(10) { if (d.feed(560.0, t) == TurnDetector.Event.SPEECH_START) s = true; t += 40 }
            return s
        }
        assertTrue(starts(TurnDetector(startFactor = LiveHear.START_FACTOR, startMin = LiveHear.START_MIN, loudNoiseFactor = LiveHear.LOUD_FACTOR)))
        assertFalse(starts(TurnDetector()))          // पुराना दरवाज़ा: यही आवाज़ अनसुनी
        assertEquals(0.0, LiveHear.farFloor(false, 900.0), 0.0)
        assertEquals(900.0, LiveHear.farFloor(true, 900.0), 0.0)
    }

    @Test fun promisedActionIsDoneByPhone() {
        assertTrue(LivePromise.actionOk("DONE: WhatsApp खुला"))
        assertFalse(LivePromise.actionOk("FAILED: timeout"))
        assertFalse(LivePromise.actionOk("ERROR boom"))
        val n = LivePromise.actionDoneNote("क्रोम खोलो", "DONE: Chrome खुला")
        assertTrue(n.contains("DONE: Chrome खुला") && n.contains("Do NOT call phone_task again"))
        assertTrue(src("GeminiLive.kt").contains("LivePromise.actionDoneNote(user, r)"))
    }

    // ------------------------------------------------------------------ याद
    @Test fun recentConversationGoesIntoNewSession() {
        val now = 10 * 3_600_000L
        val turns = listOf(
            LocalRecall.Turn(1, "u", "बहुत पुरानी बात", now - 7 * 3_600_000L),
            LocalRecall.Turn(2, "u", "मेरी बहन का नाम प्रिया है", now - 5 * 60_000L),
            LocalRecall.Turn(3, "d", "ठीक है, याद रखूँगी", now - 4 * 60_000L),
            LocalRecall.Turn(4, "u", "कल 10 बजे मीटिंग है", now - 10_000L),
        )
        val b = LocalRecall.recentBlock(turns, now)
        assertTrue(b.contains("RECENT CONVERSATION"))
        assertFalse(b.contains("बहुत पुरानी बात"))                      // 6 घंटे से पुरानी नहीं
        assertTrue(b.indexOf("प्रिया") < b.indexOf("याद रखूँगी") && b.indexOf("याद रखूँगी") < b.indexOf("मीटिंग"))
        assertTrue(b.contains("User (just now)") && b.contains("You (4 min ago)"))
        assertEquals("", LocalRecall.recentBlock(emptyList(), now))
        val many = (1..50).map { LocalRecall.Turn(it.toLong(), "u", "बात $it", now - (50 - it) * 1000L) }
        val m = LocalRecall.recentBlock(many, now, maxTurns = 20)
        assertTrue(m.contains("बात 50") && m.contains("बात 31") && !m.contains("बात 30:") && !m.contains(": बात 30\n"))
        assertTrue(src("BrainManager.kt").contains("LocalMemory.liveBlock(ctx)") && src("BrainManager.kt").contains("KEEP LEARNING"))
    }

    private fun src(name: String): String = listOf(java.io.File("src/main/java/com/dk/assistant/$name"), java.io.File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}
