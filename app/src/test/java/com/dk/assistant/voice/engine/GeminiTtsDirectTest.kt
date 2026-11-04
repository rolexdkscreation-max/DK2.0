package com.dk.assistant.voice.engine

import com.dk.assistant.voice.LiveAckRules
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** नवंबर 2026 (मालिक): Worker पूरी तरह हटा - Gemini TTS सीधे ऐप से, DK लाइव वाली key से. बर्ताव की जाँच */
class GeminiTtsDirectTest {
    private fun src(p: String): String = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p"))
        .first { it.exists() }.readText()

    @Test fun body_38_styleInSpeechMetadata() {
        val b = JSONObject(GeminiTtsDirect.body("gemini-3.8-flash-lite-tts", " नमस्ते ", "Sulafat", "कोमल आवाज़ में"))
        assertEquals("gemini-3.8-flash-lite-tts", b.getString("model"))
        val part = b.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("नमस्ते", part.getString("text"))
        val ann = part.getJSONArray("annotations").getJSONObject(0)
        assertEquals("speech_metadata", ann.getString("type"))
        assertEquals("कोमल आवाज़ में", ann.getString("style"))
        assertEquals("audio", b.getJSONObject("response_format").getString("type"))
        assertTrue(b.getBoolean("stream"))
    }

    @Test fun body_oldModel_styleBeforeText_andLimits() {
        val b = JSONObject(GeminiTtsDirect.body("gemini-3.1-flash-tts-preview", "हाँ", "Nope", "धीरे"))
        assertEquals("धीरे हाँ", b.getString("input"))
        assertEquals("Sulafat", b.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        val noStyle = JSONObject(GeminiTtsDirect.body("gemini-3.8-flash-tts", "हाँ", "Kore", "  "))
        assertEquals("हाँ", noStyle.getString("input"))
        val long = JSONObject(GeminiTtsDirect.body("gemini-3.1-flash-tts-preview", "a".repeat(900), "Kore", "s".repeat(900)))
        assertEquals(600 + 1 + 600, long.getString("input").length)
    }

    @Test fun models_goodThenWanted_skipBlocked() {
        val M = GeminiTtsDirect.MODELS
        assertEquals(M, GeminiTtsDirect.models(null, ""))
        assertEquals(listOf(M[1], M[0], M[2]), GeminiTtsDirect.models(M[1], null))
        assertEquals(listOf(M[2], M[1], M[0]), GeminiTtsDirect.models(M[1], M[2]))
        assertEquals(listOf(M[0], M[2]), GeminiTtsDirect.models(null, null) { it == M[1] })
        assertTrue(GeminiTtsDirect.models(null, null) { true }.isEmpty())
        assertEquals(M, GeminiTtsDirect.models("old-unknown", "x"))
    }

    @Test fun sse_audioDeltaOnly() {
        val pcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        assertArrayEquals(pcm, GeminiTtsDirect.pcmFromLine("data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"$b64\"}}"))
        assertArrayEquals(pcm, GeminiTtsDirect.pcmFromLine("data:{\"type\":\"step.delta\",\"delta\":{\"type\":\"audio\",\"data\":\"$b64\"}}"))
        assertNull(GeminiTtsDirect.pcmFromLine("data: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\"hi\"}}"))
        assertNull(GeminiTtsDirect.pcmFromLine("data: [DONE]"))
        assertNull(GeminiTtsDirect.pcmFromLine("event: ping"))
        assertNull(GeminiTtsDirect.pcmFromLine("data: {broken"))
        assertNull(GeminiTtsDirect.pcmFromLine(""))
    }

    @Test fun wavHeaderStripped_andJsonFallback() {
        val pcm = ByteArray(100) { it.toByte() }
        val wav = com.dk.assistant.GeminiTts.pcmToWav(pcm, 24000)
        assertArrayEquals(pcm, GeminiTtsDirect.stripWav(wav))
        assertArrayEquals(pcm, GeminiTtsDirect.stripWav(pcm))
        val b64 = Base64.getEncoder().encodeToString(wav)
        val j = "{\"steps\":[{\"content\":[{\"type\":\"text\",\"text\":\"x\"},{\"type\":\"audio\",\"data\":\"$b64\"}]}]}"
        assertArrayEquals(pcm, GeminiTtsDirect.pcmFromJson(j))
        assertEquals(0, GeminiTtsDirect.pcmFromJson("not json").size)
    }

    /** Worker, उसका पता और APP_TOKEN - कहीं नहीं; key सिर्फ़ DK लाइव वाली */
    @Test fun workerGone_noAddressOrTokenNeeded() {
        for (f in listOf("../worker/dk-brain/src/index.js", "worker/dk-brain/src/index.js")) assertFalse(f, File(f).exists())
        for (f in listOf("src/main/java/com/dk/assistant/voice/engine/WorkerWhy.kt", "app/src/main/java/com/dk/assistant/voice/engine/WorkerWhy.kt"))
            assertFalse(f, File(f).exists())
        val e = src("voice/engine/GeminiTtsEngine.kt")
        for (bad in listOf("workerUrl", "worker_url", "worker_token", "/tts", "/ping", "dk-brain का पता", "APP_TOKEN"))
            assertFalse(bad, e.contains(bad))
        assertTrue(e.contains("com.dk.assistant.Prefs.liveGeminiKeys(c)"))
        assertTrue(e.contains("if (keys(c).isEmpty()) GeminiTtsDirect.NO_KEY else null"))
        // 200 पर खाली आवाज़ = नाकामी (Hey DK ack वाला बार-बार माँगना न लौटे)
        assertTrue(e.contains("if (got < GeminiTtsDirect.MIN_PCM_BYTES) throw IOException("))
        val card = src("voice/engine/VoiceEngineCard.kt")
        assertFalse(card.contains("Worker जो चले"))
    }

    /** Hey DK ack: एक समय पर एक ही बनाने वाला - 8 थ्रेड एक साथ = सिर्फ़ 1 अंदर */
    @Test fun ackOnlyOneAtATime() {
        val busy = AtomicBoolean(false)
        val inside = AtomicInteger(0)
        val start = CountDownLatch(1)
        val ts = (1..8).map { Thread { start.await(); if (LiveAckRules.tryBegin(busy)) inside.incrementAndGet() } }
        ts.forEach { it.start() }; start.countDown(); ts.forEach { it.join() }
        assertEquals(1, inside.get())
        assertFalse(LiveAckRules.tryBegin(busy))
        busy.set(false)
        assertTrue(LiveAckRules.tryBegin(busy))
        val hey = src("voice/HeyDk.kt")
        assertTrue(hey.contains("if (!LiveAckRules.tryBegin(ackBusy)) return"))
        assertTrue(hey.contains("finally { ackBusy.set(false) }"))
    }
}
