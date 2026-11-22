package com.dk.assistant.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 22 नवंबर 2026 (मालिक): "लॉक-अनलॉक में एक ऑप्शन - सिर्फ़ मालिक की आवाज़ पर; बंद हो तो कोई भी बोले तो लॉक / अनलॉक".
 * और "अनलॉक बोलते ही तुरंत" - आवाज़ की जाँच स्क्रीन जगने के साथ-साथ ([UnlockGuard.prefetch]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OwnerOnlyUnlockTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private class Fake(var ready: Boolean = true, var score: Float = 0.80f) : UnlockGuard.Voice {
        var calls = 0
        override fun ready(c: Context) = ready
        override fun score(c: Context, pcm: ByteArray): Pair<Float, FloatArray>? { calls++; return score to floatArrayOf(1f, 0f, 0f, 0f) }
        override fun baseThreshold(c: Context) = 0.55f
        override fun seen(c: Context) = emptyList<FloatArray>()
        override fun remember(c: Context, e: FloatArray) { /* नकली */ }
    }

    private lateinit var fake: Fake

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

    @Test fun switchOff_anyoneUnlocks_noVoiceCheck() = runBlocking {
        fake.score = 0.10f                                   // किसी और की आवाज़
        UnlockGuard.heard(speech()); UnlockGuard.intent()
        assertNull(UnlockGuard.allow(c, ownerOnly = false))
        assertEquals("switch बंद = आवाज़ की जाँच ही नहीं (तुरंत)", 0, fake.calls)
    }

    @Test fun switchOff_stillNeedsPhoneHeardUnlock() = runBlocking {
        // AI / बाहर की लिखावट ने अनलॉक माँगा (फ़ोन के सुनने ने नहीं) = PIN नहीं, switch बंद हो तब भी
        val r = UnlockGuard.allow(c, ownerOnly = false)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.NO_INTENT.hi))
    }

    @Test fun switchOn_otherVoice_refused() = runBlocking {
        fake.score = 0.10f
        UnlockGuard.heard(speech()); UnlockGuard.intent()
        val r = UnlockGuard.allow(c, ownerOnly = true)
        assertTrue(r!!, r.contains(UnlockRules.Verdict.LOW.hi))
    }

    @Test fun prefetch_resultUsedByAllow() = runBlocking {
        UnlockGuard.heard(speech()); UnlockGuard.intent()
        UnlockGuard.prefetch(c, ownerOnly = true, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertNull(UnlockGuard.allow(c))
        assertEquals("जाँच एक ही बार (साथ-साथ वाली)", 1, fake.calls)
    }

    @Test fun prefetch_switchOff_otherVoiceOk() = runBlocking {
        fake.score = 0.10f
        UnlockGuard.heard(speech()); UnlockGuard.intent()
        UnlockGuard.prefetch(c, ownerOnly = false, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertNull(UnlockGuard.allow(c))
    }

    @Test fun lock_switchOn_otherVoice_refused() = runBlocking {
        fake.score = 0.10f
        UnlockGuard.heard(speech())
        val r = UnlockGuard.lockAllow(c, ownerOnly = true)
        assertNotNull(r); assertTrue(r!!, r.contains("लॉक नहीं किया"))
    }

    @Test fun lock_switchOff_orGuardianNotTaught_locks() = runBlocking {
        fake.score = 0.10f
        UnlockGuard.heard(speech())
        assertNull(UnlockGuard.lockAllow(c, ownerOnly = false))
        fake.ready = false
        assertNull("वॉइस गार्डियन सिखाया ही नहीं = पहले जैसा लॉक", UnlockGuard.lockAllow(c, ownerOnly = true))
    }

    @Test fun lock_owner_locks() = runBlocking {
        UnlockGuard.heard(speech())
        assertNull(UnlockGuard.lockAllow(c, ownerOnly = true))
    }
}
