package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): DK का अपना दिमाग - सीखे, फ़ोन पर रखे, key न चले तो उसी से, पर कभी गलत जवाब नहीं */
class DkMindTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()
    private val day = 86_400_000L

    @Test fun learnsStableTalk_notChangingOrActions() {
        assertTrue(DkMind.teachable("गुरुत्वाकर्षण क्या होता है", "जो बल हर चीज़ को धरती की ओर खींचता है, उसे गुरुत्वाकर्षण कहते हैं।"))
        assertTrue(DkMind.teachable("मुझे गुस्सा बहुत आता है क्या करूँ", "गहरी साँस लीजिए, दस तक गिनिए और पानी पीजिए।"))
        // बदलने वाली / खोज वाली = सिर्फ़ API
        for (q in listOf("आज मौसम कैसा है", "भारत के प्रधानमंत्री कौन हैं", "सोने का भाव बताओ", "2026 का विजेता कौन", "latest iphone कौन सा है", "मैच का स्कोर क्या है"))
            assertFalse(q, DkMind.teachable(q, "कुछ भी जवाब यहाँ"))
        // फ़ोन का काम / आधी बात / गलती वाला जवाब = नहीं
        assertFalse(DkMind.teachable("व्हाट्सएप खोलो", "खोल दिया"))
        assertFalse(DkMind.teachable("इसका मतलब क्या है", "इसका मतलब कुछ ऐसा है"))
        assertFalse(DkMind.teachable("गुरुत्वाकर्षण क्या होता है", "माफ़ कीजिए, अभी जवाब नहीं आ पाया"))
        assertFalse(DkMind.teachable("गुरुत्वाकर्षण क्या होता है", "API key की limit खत्म"))
        assertFalse(DkMind.teachable("गुरुत्वाकर्षण क्या होता है", "x".repeat(DkMind.MAX_ANSWER + 1)))
    }

    @Test fun neverWrongAnswer_numbersAndNoMustMatch() {
        val now = 100 * day
        val all = listOf(DkMind.Entry("5 का पहाड़ा बताओ", "5, 10, 15, 20, 25", now), DkMind.Entry("मुझे चाय पसंद है क्या", "हाँ, आपको चाय पसंद है।", now))
        assertNull(DkMind.match(all, "6 का पहाड़ा बताओ", exact = false, now = now))
        assertEquals("5, 10, 15, 20, 25", DkMind.match(all, "5 का पहाड़ा बताओ", exact = false, now = now)!!.a)
        assertNull(DkMind.match(all, "मुझे चाय पसंद नहीं है क्या", exact = false, now = now))
        assertEquals(0.0, DkMind.similarity("मुझे चाय पसंद है", "मुझे चाय पसंद नहीं है"), 0.0)
        // बदलने वाली बात - सीखा हो तब भी याद से नहीं
        assertNull(DkMind.match(listOf(DkMind.Entry("आज मौसम कैसा है", "धूप है", now)), "आज मौसम कैसा है", exact = false, now = now))
    }

    @Test fun ownBrainFirstOnlyWhenExactAndFresh_anyAgeWhenKeyFails() {
        val now = 200 * day
        val e = DkMind.Entry("गुरुत्वाकर्षण क्या होता है", "धरती की खींचने वाली ताकत।", now - 10 * day)
        assertEquals(e, DkMind.match(listOf(e), e.q, exact = true, now = now))
        val old = e.copy(t = now - 100 * day)
        assertNull(DkMind.match(listOf(old), old.q, exact = true, now = now))          // API चलती है = ताज़ा API से
        assertEquals(old, DkMind.match(listOf(old), old.q, exact = false, now = now))  // key नहीं चली = पुराना भी काम का
        assertNull(DkMind.match(listOf(e), "गुरुत्वाकर्षण की खोज किसने की", exact = true, now = now))
        assertEquals(e, DkMind.match(listOf(e), "गुरुत्वाकर्षण क्या होता है भला", exact = false, now = now))
    }

    @Test fun growsKeepsCountsAndMergesBackupWithoutLoss() {
        var all = emptyList<DkMind.Entry>()
        all = DkMind.add(all, "चाय कैसे बनती है", "पानी, दूध, पत्ती, चीनी।", 1)
        all = DkMind.add(all, "चाय कैसे बनती है", "पानी उबालें, पत्ती, दूध, चीनी डालें।", 2)
        assertEquals(1, all.size); assertEquals(2, all[0].n); assertEquals("पानी उबालें, पत्ती, दूध, चीनी डालें।", all[0].a)
        val backup = listOf(DkMind.Entry("चाय कैसे बनती है", "पुराना", 0), DkMind.Entry("नींद क्यों आती है", "शरीर आराम माँगता है।", 1))
        val m = DkMind.merge(all, backup)
        assertEquals(2, m.size)
        assertEquals("पानी उबालें, पत्ती, दूध, चीनी डालें।", m.first { it.q == "चाय कैसे बनती है" }.a)   // नया सीखा जवाब बचा
        assertEquals(m, DkMind.fromJson(DkMind.toJson(m)))
        assertEquals(m.map { it.q to it.a }, DkMind.merge(m, m).map { it.q to it.a })   // दोबारा रिस्टोर = कुछ दोहराया नहीं
        // पुरानी फ़ाइल (n नहीं) भी पढ़ी जाए
        assertEquals(1, DkMind.fromJson("""[{"q":"चाय कैसे बनती है","a":"x","t":5}]""")[0].n)
        assertTrue(DkMind.MAX >= 20_000)
        val big = (1..DkMind.MAX + 5).map { DkMind.Entry("सवाल नंबर $it क्या", "जवाब", it.toLong(), if (it == 1) 9 else 1) }
        val cut = DkMind.trim(big)
        assertEquals(DkMind.MAX, cut.size)
        assertTrue(cut.any { it.q == "सवाल नंबर 1 क्या" })     // बार-बार आई पुरानी बात बची
    }

    @Test fun liveTeachesOnlyPlainTalk() {
        assertTrue(DkMind.liveTeach("नींद क्यों आती है", "शरीर आराम माँगता है।", searched = false, toolRunning = false, sinceToolMs = 60_000))
        assertFalse(DkMind.liveTeach("नींद क्यों आती है", "…", searched = true, toolRunning = false, sinceToolMs = 60_000))
        assertFalse(DkMind.liveTeach("नींद क्यों आती है", "…", searched = false, toolRunning = true, sinceToolMs = 60_000))
        assertFalse(DkMind.liveTeach("नींद क्यों आती है", "…", searched = false, toolRunning = false, sinceToolMs = 3_000))
    }

    @Test fun wiredEverywhere_andHiddenFromApp() {
        assertTrue(src("GeminiLiveTurn.kt").contains("DkMind.liveTeach(u, r, searched, toolJobs.isNotEmpty(), System.currentTimeMillis() - lastToolAt)"))
        val a = src("AgentRunner.kt")
        assertTrue(a.contains("ownMind(goal)?.let { say(it); return ChatOut.DONE }"))
        assertTrue(a.contains("if (OfflineTalk.needsNet(goal)) return null"))
        assertTrue(a.contains("LocalBrain.recall(svc, goal, exact = true)"))
        assertTrue(src("backup/BackupPhone.kt").contains("brain = com.dk.assistant.LocalBrain.exportJson(c)"))
        assertTrue(src("backup/BackupPhone.kt").contains("com.dk.assistant.LocalBrain.importJson(c, m.brain)"))
        // ऐप में कहीं नहीं दिखता (सेटिंग / सर्च में कोई विकल्प नहीं)
        for (f in listOf("SettingsActivity.kt", "SettingsBrain.kt", "SettingsSearch.kt")) assertFalse(f, src(f).contains("DkMind"))
    }

    @Test fun liveWaitsForWhisperOnlyWhenCaptionFoundNothing() {
        val t = src("GeminiLiveTurnEnd.kt")
        assertTrue(t.contains("if (q == null && !ringing && LiveWhisper.on(svc))"))
        assertTrue(t.indexOf("var q = LiveFast.pick(b") < t.indexOf("LiveWhisper.on(svc)"))
    }
}
