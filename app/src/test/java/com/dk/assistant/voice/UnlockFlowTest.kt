package com.dk.assistant.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 16 नवंबर 2026 (मालिक: "merge से पहले आवाज़ अनलॉक की जाँच करो").
 * आवाज़ से अनलॉक का पूरा क्रम - फ़ोन के सुनने वाले रास्ते जैसा: बात सुनी ([UnlockGuard.heard]) → "अनलॉक करो" पहचाना
 * ([UnlockGuard.intent]) → PIN से ठीक पहले [UnlockGuard.allow]. आवाज़-पहचान के अंक नकली ([UnlockGuard.Voice]),
 * बाकी सब असली code (समय, सीमा, replay, 3 गलती = रोक).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnlockFlowTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private class Fake : UnlockGuard.Voice {
        var ready = true
        var score = 0.80f
        var emb = floatArrayOf(1f, 0f, 0f, 0f)
        val seen = ArrayList<FloatArray>()
        val remembered = ArrayList<FloatArray>()
        var calls = 0
        override fun ready(c: Context) = ready
        override fun score(c: Context, pcm: ByteArray): Pair<Float, FloatArray>? { calls++; return score to emb }
        override fun baseThreshold(c: Context) = 0.55f            // सख़्त सीमा = 0.65
        override fun seen(c: Context) = seen + remembered
        override fun remember(c: Context, e: FloatArray) { remembered += e }
    }

    private lateinit var fake: Fake

    /** 1.5 सेकंड की असली जैसी (चुप नहीं) आवाज़ - 16 kHz PCM16 */
    private fun speech(ms: Int = 1500): ByteArray {
        val n = 16 * ms
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (8000 * kotlin.math.sin(i * 0.07) * (0.6 + 0.4 * kotlin.math.sin(i * 0.001))).toInt()
            out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Before fun setUp() { fake = Fake(); UnlockGuard.voice = fake; UnlockGuard.reset() }
    @After fun tearDown() { UnlockGuard.reset() }

    private fun ownerSaysUnlock(score: Float = 0.80f, emb: FloatArray = floatArrayOf(1f, 0f, 0f, 0f)): String? = runBlocking {
        fake.score = score; fake.emb = emb
        UnlockGuard.heard(speech()); UnlockGuard.intent()
        UnlockGuard.allow(c)
    }

    @Test fun ownerVoice_unlocks_andIsRemembered() {
        assertNull(ownerSaysUnlock())
        assertEquals(1, fake.remembered.size)
    }

    @Test fun noSpokenUnlock_AIorText_isRefused() = runBlocking {
        UnlockGuard.heard(speech())                       // मालिक कुछ बोला, पर "अनलॉक" नहीं कहा (AI / बाहर की लिखावट का कदम)
        val r = UnlockGuard.allow(c)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_INTENT.hi))
        assertEquals(0, fake.calls)
    }

    @Test fun strangerVoice_refused_threeTimesPauses() {
        repeat(3) { i ->
            val r = ownerSaysUnlock(score = 0.40f, emb = floatArrayOf(0f, 1f, i.toFloat(), 0f))
            assertTrue(r!!, r.contains(UnlockRules.Verdict.LOW.hi))
        }
        // अब असली मालिक भी 30 मिनट तक आवाज़ से नहीं खोल सकता (रिकॉर्डिंग आज़माने वाले से बचाव)
        val r = ownerSaysUnlock()
        assertTrue(r!!, r.contains(UnlockRules.Verdict.PAUSED.hi))
        UnlockGuard.reset()                               // नया PIN सेव / आवाज़ दोबारा सिखाई = रोक हटी
        assertNull(ownerSaysUnlock())
    }

    @Test fun replayOfPreviousUnlock_refused() {
        val e = floatArrayOf(0.6f, 0.8f, 0f, 0f)
        assertNull(ownerSaysUnlock(emb = e))              // पहली बार असली
        val r = ownerSaysUnlock(emb = e.copyOf())         // वही रिकॉर्डिंग दोबारा बजाई
        assertTrue(r!!, r.contains(UnlockRules.Verdict.REPLAY.hi))
    }

    @Test fun replayOfEnrollmentSample_refused() {
        fake.seen += floatArrayOf(0f, 0f, 1f, 0f)          // Voice Guardian में सिखाते समय का नमूना
        val r = ownerSaysUnlock(emb = floatArrayOf(0f, 0.01f, 1f, 0f))
        assertTrue(r!!, r.contains(UnlockRules.Verdict.REPLAY.hi))
    }

    @Test fun oldSpeech_notUsed() = runBlocking {
        val now = System.currentTimeMillis()
        UnlockGuard.heard(speech(), now - 15_000)         // 15 सेकंड पहले की बात
        UnlockGuard.intent(now)
        val r = UnlockGuard.allow(c)                      // 3 सेकंड नई बात का इंतज़ार, फिर मना
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_VOICE.hi))
    }

    @Test fun staleIntent_refused() = runBlocking {
        UnlockGuard.heard(speech())
        UnlockGuard.intent(System.currentTimeMillis() - 25_000)   // 25 सेकंड पहले कहा था
        val r = UnlockGuard.allow(c)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_INTENT.hi))
    }

    @Test fun speechFinishingJustAfterIntent_accepted() = runBlocking {
        UnlockGuard.intent()                              // Live caption ने बात के बीच में "अनलॉक" पकड़ा
        val t = Thread { Thread.sleep(800); UnlockGuard.heard(speech()) }; t.start()
        assertNull(UnlockGuard.allow(c))
        t.join()
    }

    @Test fun tooShortSpeech_ignored() = runBlocking {
        UnlockGuard.heard(speech(300))
        UnlockGuard.intent()
        val r = UnlockGuard.allow(c)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_VOICE.hi))
    }

    @Test fun guardianNotTaught_refused() {
        fake.ready = false
        val r = ownerSaysUnlock()
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NOT_READY.hi))
    }

    @Test fun oneIntent_oneUnlock() = runBlocking {
        assertNull(ownerSaysUnlock())
        val r = UnlockGuard.allow(c)                      // बिना दोबारा कहे फिर से PIN नहीं
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_INTENT.hi))
    }

    @Test fun voiceCommandPath_grantsOnce() = runBlocking {
        fake.emb = floatArrayOf(0f, 0f, 0f, 1f)
        assertTrue(UnlockGuard.checkCommand(c, speech()).ok)
        assertNull(UnlockGuard.allow(c))
        val r = UnlockGuard.allow(c)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_INTENT.hi))
    }
}
