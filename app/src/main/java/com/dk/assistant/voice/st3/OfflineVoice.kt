package com.dk.assistant.voice.st3

import android.content.Context
import android.media.AudioAttributes
import com.dk.assistant.DKLog
import com.dk.assistant.Quiet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 20 नवंबर 2026 (मालिक: "Gemini की आवाज़ न आए / सिर्फ़ टेक्स्ट आए / limit खत्म = फ़ोन में लगा ऑफ़लाइन TTS बोले;
 * चार्जर / Wi-Fi वाली घोषणाएँ और टेक्स्ट चैट का 'बोलकर भी' भी यही बोले"):
 * DK की ऑफ़लाइन आवाज़ (Supertonic 3, [St3Engine]) से एक वाक्य सीधे speaker पर - बातचीत के बाहर का एक साझा रास्ता.
 *
 * - [ready]: मॉडल + कोई आवाज़ डाउनलोड है (Settings → आवाज़ → "आवाज़ें खोलें"). नहीं = false, बुलाने वाला अपना पुराना रास्ता ले.
 * - [say]: बोलो और ख़त्म होने तक रुको. true = कुछ आवाज़ बजी. एक समय में एक (कतार) - घोषणाएँ एक-दूसरे पर न चढ़ें.
 * - [stop]: अभी जो यह बोल रहा है वह तुरंत चुप. चलती बातचीत की आवाज़ (Live का बँटवारा) को यह नहीं छूता,
 *   जब तक यह खुद कुछ बोल न रहा हो.
 */
object OfflineVoice {
    private val lock = Mutex()
    @Volatile private var player: St3Player? = null
    /** हर [stop] पर बढ़ता है - पुराना बोलना रुके */
    private val gen = AtomicInteger(0)
    @Volatile private var active = false

    /** मॉडल + आवाज़ फ़ोन में है? (गलती = नहीं) */
    fun ready(c: Context): Boolean = try {
        com.dk.assistant.voice.engine.St3VoiceEngine.notReady(c.applicationContext) == null
    } catch (e: Throwable) { Quiet.note(e); false }

    /** अभी बोल रहा है */
    val speaking: Boolean get() = active

    /**
     * [text] ऑफ़लाइन आवाज़ में. [usage] = AudioAttributes usage (कॉल के समय accessibility, वरना assistant).
     * false = मॉडल नहीं / गलती / रोका गया, कुछ नहीं बजा - तब बुलाने वाला दूसरा रास्ता ले.
     */
    suspend fun say(c: Context, text: String, usage: Int = AudioAttributes.USAGE_ASSISTANT): Boolean {
        if (text.isBlank()) return false
        val app = c.applicationContext
        if (!ready(app)) return false
        val clean = St3Text.onlyTags(com.dk.assistant.voice.engine.HinglishText.toSpeakable(text))
        if (clean.isBlank()) return false
        return lock.withLock {
            val my = gen.get()
            val p = St3Player(usage = usage).also { player = it }
            val q = Channel<ByteArray>(capacity = 2)
            var played = 0L
            var failure: String? = null
            active = true
            try {
                coroutineScope {
                    launch(Dispatchers.Default) {
                        try {
                            var first = true
                            St3Engine.synthBlocking(app, clean, St3Store.voice(app)) { wav ->
                                if (gen.get() != my) return@synthBlocking
                                if (!first) kotlinx.coroutines.runBlocking { q.send(St3Synth.silence(250)) }
                                first = false
                                kotlinx.coroutines.runBlocking { q.send(St3Synth.pcm16(wav)) }
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Throwable) {
                            Quiet.note(e); failure = e.message ?: e.javaClass.simpleName
                        } finally { q.close() }
                    }
                    launch(Dispatchers.IO) {
                        for (b in q) {
                            if (gen.get() != my || !p.write(b)) break
                            played += b.size
                        }
                        if (gen.get() == my) p.drain(played)
                    }
                }
            } finally {
                p.stop(); if (player === p) player = null
                active = false
            }
            failure?.let { DKLog.d("ऑफ़लाइन आवाज़ नहीं बोली: ${it.take(120)}") }
            played > 0
        }
    }

    /** यह जो बोल रहा है, अभी चुप (बनना भी रुके) */
    fun stop() {
        if (!active) return
        gen.incrementAndGet()
        player?.stop()
        St3Engine.stop()
    }
}
