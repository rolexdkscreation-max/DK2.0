package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): "एक key में सब उसी पर चले, पर भारी काम बातचीत वाले मॉडल से नहीं - बातचीत की free request वैसी ही रहे.
 * DK का अपना दिमाग हमेशा सीखता रहे और ऑफलाइन में भी अच्छे से चले."
 */
class OneKeyLanesTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private val T = OneKeyLanes.Lane.TALK
    private val P = OneKeyLanes.Lane.PHONE
    private val H = OneKeyLanes.Lane.HEAVY

    @Test fun heavyWork_neverTouchesTalkModel() {
        val coder = OneKeyLanes.order(H, GeminiModels.CODER_CHAIN, enabled = true)
        assertEquals(listOf(GeminiModels.CODER, "gemini-3.6-flash", "gemini-3-flash-preview"), coder)
        assertFalse(OneKeyLanes.TALK_MODEL in OneKeyLanes.order(H, GeminiModels.LIGHT_CHAIN, enabled = true))
        assertFalse(OneKeyLanes.TALK_MODEL in OneKeyLanes.order(H, GeminiClient.BACKUP_MODELS, enabled = true))
        assertFalse(OneKeyLanes.PHONE_MODEL in OneKeyLanes.order(H, GeminiClient.BACKUP_MODELS, enabled = true))
        // गहरी सोच का क्रम बना रहे (18 नवंबर 2026 मालिक: Hydrogen सबसे पहले, फिर Argon / Pro)
        val th = OneKeyLanes.order(H, GeminiClient.THINK_MODELS + GeminiClient.BACKUP_MODELS, enabled = true)
        assertEquals("gemini-4-hydrogen", th.first())
        assertEquals("gemini-4-argon", th[1])
    }

    @Test fun phoneWork_ownModels_notTalk() {
        val ph = OneKeyLanes.order(P, GeminiModels.order(OneKeyLanes.TALK_MODEL, GeminiClient.BACKUP_MODELS), enabled = true)
        assertEquals(listOf(OneKeyLanes.PHONE_MODEL, OneKeyLanes.SHARED), ph)
        assertFalse(GeminiModels.CODER in ph)
    }

    @Test fun talk_firstOwn_thenSpare_neverStops() {
        val t = OneKeyLanes.order(T, GeminiModels.order(OneKeyLanes.TALK_MODEL, GeminiClient.BACKUP_MODELS), enabled = true)
        assertEquals(OneKeyLanes.TALK_MODEL, t.first())
        assertTrue(t.size >= 2)                                   // अपनी limit खत्म = उधार, बात नहीं रुकती
        assertFalse(GeminiModels.CODER in t)                      // भारी लेन का मॉडल नहीं
    }

    @Test fun offOrNoLane_unchanged_chosenFirst_neverEmpty() {
        val l = GeminiClient.BACKUP_MODELS
        assertEquals(l, OneKeyLanes.order(H, l, enabled = false))
        assertEquals(l, OneKeyLanes.order(null, l, enabled = true))
        assertEquals("gemini-3.8-flash", OneKeyLanes.order(T, listOf("gemini-3.8-flash") + l, keepFirst = true, enabled = true).first())
        assertTrue(OneKeyLanes.order(P, listOf("gemini-flash-latest"), enabled = true).contains("gemini-flash-latest"))
        assertTrue(OneKeyLanes.order(P, listOf("x"), enabled = true).isNotEmpty())
    }

    @Test fun lanes_disjointOwnModels() {
        val all = OneKeyLanes.OWN.values.flatten()
        assertEquals(all.size, all.distinct().size)
        assertTrue(OneKeyLanes.SPARE.values.flatten().none { it == OneKeyLanes.TALK_MODEL })   // बातचीत का मॉडल कोई उधार नहीं लेता
    }

    @Test fun health_separatePerLane() {
        assertEquals("gemini", OneKeyLanes.healthId("gemini", T, enabled = true))
        assertEquals("gemini:phone", OneKeyLanes.healthId("gemini", P, enabled = true))
        assertEquals("gemini", OneKeyLanes.healthId("gemini", P, enabled = false))
        assertEquals("groq", OneKeyLanes.healthId("groq", P, enabled = true))
        assertEquals(H, OneKeyLanes.forScope(ApiScopes.CODER)); assertEquals(H, OneKeyLanes.forScope(ApiScopes.PC))
        assertEquals(T, OneKeyLanes.forScope(ApiScopes.CHAT))
    }

    @Test fun wiring_everyHeavyAndPhonePath() {
        val b = src("BrainManager.kt")
        assertTrue(b.contains("lane = OneKeyLanes.forScope(scope)"))
        assertTrue(b.contains("client.lane = if (chat) OneKeyLanes.Lane.TALK else OneKeyLanes.Lane.PHONE"))
        assertTrue(b.contains("ProviderHealth.fail(hid(p), e)"))
        assertTrue(b.contains("c.lane = OneKeyLanes.Lane.TALK"))
        assertTrue(b.contains("lane: OneKeyLanes.Lane = OneKeyLanes.Lane.PHONE"))
        assertTrue(src("Prefs.kt").contains(".also { OneKeyLanes.on = it }"))
        assertTrue(src("CoderAi.kt").contains("if (pinned) base else OneKeyLanes.order(OneKeyLanes.Lane.HEAVY, base)"))
        assertTrue(src("StoryWriter.kt").contains("OneKeyLanes.order(OneKeyLanes.Lane.HEAVY"))
        assertTrue(src("forge/WebsiteForgeClient.kt").contains("OneKeyLanes.Lane.HEAVY"))
        assertTrue(src("research/DeepResearchRun.kt").contains("OneKeyLanes.Lane.HEAVY"))
        assertTrue(src("insights/InsightsAgent.kt").contains("OneKeyLanes.order(OneKeyLanes.Lane.HEAVY"))
        assertTrue(src("ScreenLook.kt").contains("OneKeyLanes.order(OneKeyLanes.Lane.PHONE"))
        assertTrue(src("AiClients.kt").contains("laned(it, if (think || deep) OneKeyLanes.Lane.HEAVY else lane ?: OneKeyLanes.Lane.TALK)"))
    }

    // ---------------------------------------------------------------- DK का अपना दिमाग
    @Test fun ownMind_worthLearning() {
        assertTrue(OwnMind.worth("गुरुत्वाकर्षण वह बल है जो चीज़ों को नीचे खींचता है।"))
        assertFalse(OwnMind.worth("{\"say\":\"x\"}"))
        assertFalse(OwnMind.worth("…"))
        assertFalse(OwnMind.worth("फ़ोन पर काम करने के लिए पहले Accessibility में DK चालू करें।"))
    }

    @Test fun ownMind_learnsEverywhere_offlineFirst() {
        for (f in listOf("ChatActivity.kt", "dkai/DkaiScreen.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("OwnMind.learn("))
            assertTrue(f, s.contains("OwnMind.offline("))
            assertTrue(f, s.contains("OwnMind.saved("))
        }
        val r = src("AgentRunner.kt")
        assertTrue(r.contains("if (chatMode && !NetProbe.online(svc)) { say(OwnMind.offline(svc, goal)); return }"))
        assertTrue(r.contains("OwnMind.learn(svc, goal, answer, ground != null)"))
    }
}
