package com.dk.assistant.voice.st3

import android.content.Context
import com.dk.assistant.Quiet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 19 नवंबर 2026: Supertonic 3 से फ़ोन पर ही बोलना.
 * - [synthBlocking]: एक टुकड़ा -> आवाज़ (44.1 kHz float). बातचीत वाला इंजन ([com.dk.assistant.voice.engine.St3VoiceEngine])
 *   और Settings का टेस्ट, दोनों यही बुलाते हैं. मॉडल एक बार खुलता है और खुला रहता है (एक समय में एक टुकड़ा - lock).
 * - [say]: Settings का "टेस्ट वाक्य" - दो coroutine: अगला टुकड़ा बनता है, उसी समय पिछला बजता है.
 * - [stop]: अभी जो बन रहा है वह तुरंत रुके (कदमों के बीच देखा जाता है).
 */
object St3Engine {
    private val lock = Any()
    @Volatile private var synth: St3Synth? = null
    private var style: St3Synth.Style? = null
    @Volatile private var player: St3Player? = null
    /** हर [stop] पर बढ़ता है - पुराने काम अपने-आप रुकें */
    private val gen = AtomicInteger(0)
    @Volatile private var warming = false

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** lock के अंदर: मॉडल + आवाज़ तैयार. null = ठीक, वरना हिंदी कारण */
    private fun ensure(c: Context, wantVoice: String?): String? {
        if (!St3Store.coreReady(c)) return "आवाज़ का मुख्य मॉडल अभी डाउनलोड नहीं हुआ"
        val vid = wantVoice?.takeIf { St3Store.voiceReady(c, it) } ?: St3Store.usableVoice(c)
            ?: return "कोई आवाज़ (आवाज़ 1-5) अभी डाउनलोड नहीं हुई"
        return try {
            val s = synth ?: St3Synth.open(St3Store.files(c), threads()).also { synth = it }
            if (style?.id != vid) {
                style?.close(); style = null
                val f = com.dk.assistant.plus.models.ModelManager.file(c, St3Config.voice(vid).asset)
                style = s.loadStyle(vid, f.readText())
            }
            null
        } catch (e: Throwable) {
            Quiet.note(e); releaseNow()
            "आवाज़ का मॉडल खुल नहीं पाया (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /**
     * एक text बोलने लायक आवाज़ बनाओ (रुककर - पीछे के धागे पर बुलाओ). हर टुकड़ा (~300 अक्षर तक) बनते ही [onPiece].
     * गलती = exception (हिंदी में). [stop] हुआ = चुपचाप लौटे.
     */
    fun synthBlocking(c: Context, text: String, voice: String?, onPiece: (FloatArray) -> Unit) {
        val my = gen.get()
        synchronized(lock) {
            if (gen.get() != my) return
            ensure(c, voice)?.let { throw IllegalStateException(it) }
            val s = synth ?: throw IllegalStateException("आवाज़ का मॉडल तैयार नहीं")
            val st = style ?: throw IllegalStateException("आवाज़ तैयार नहीं")
            val steps = St3Store.steps(c); val speed = St3Store.speed(c)
            for (piece in St3Text.chunk(text)) {
                if (gen.get() != my) return
                val out = s.synth(piece, st, steps, speed) { gen.get() != my } ?: continue
                if (gen.get() != my) return
                onPiece(out.wav)
            }
        }
    }

    /** मॉडल पहले से खोल लो (पहला जवाब जल्दी). सस्ता: खुला हो / खुल रहा हो तो कुछ नहीं */
    fun warm(c: Context) {
        if (synth != null || warming || !St3Store.ready(c)) return
        warming = true
        val app = c.applicationContext
        Thread({
            try { synchronized(lock) { ensure(app, St3Store.voice(app)) } } catch (e: Throwable) { Quiet.note(e) } finally { warming = false }
        }, "dk-st3-warm").apply { isDaemon = true; start() }
    }

    /** Settings का टेस्ट: [text] सीधे speaker पर. null = पूरा बोला (या रोका गया), वरना हिंदी में गलती */
    suspend fun say(c: Context, text: String): String? {
        stop()
        val my = gen.get()
        val p = St3Player().also { player = it }
        val q = Channel<ByteArray>(capacity = 2)
        var failure: String? = null
        try {
            coroutineScope {
                launch(Dispatchers.Default) {
                    try {
                        var first = true
                        synthBlocking(c, text, St3Store.voice(c)) { wav ->
                            if (!first) kotlinx.coroutines.runBlocking { q.send(St3Synth.silence(300)) }
                            first = false
                            kotlinx.coroutines.runBlocking { q.send(St3Synth.pcm16(wav)) }
                        }
                    } catch (e: Throwable) {
                        Quiet.note(e); failure = e.message ?: e.javaClass.simpleName
                    } finally { q.close() }
                }
                launch(Dispatchers.IO) {
                    var total = 0L
                    for (b in q) {
                        if (gen.get() != my || !p.write(b)) break
                        total += b.size
                    }
                    p.drain(total)
                }
            }
        } finally { p.stop(); if (player === p) player = null }
        return failure
    }

    /** अभी बोलना / बनाना बंद (किसी भी धागे से) */
    fun stop() { gen.incrementAndGet(); player?.stop() }

    private fun releaseNow() {
        try { style?.close() } catch (ignored: Exception) {}
        style = null
        try { synth?.close() } catch (ignored: Exception) {}
        synth = null
    }

    /** RAM खाली करो (मॉडल हटाते समय) - रुककर, पीछे के धागे पर */
    fun release() { stop(); synchronized(lock) { releaseNow() } }
}
