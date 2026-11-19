package com.dk.assistant.voice.st3

import android.content.Context
import com.dk.assistant.Quiet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 19 नवंबर 2026 (चरण 1): Supertonic 3 से फ़ोन पर ही बोलना. अभी सिर्फ़ Settings का "टेस्ट वाक्य" इसे बुलाता है -
 * DK की बातचीत में अभी नहीं जुड़ा (वह चरण 2-3 में, पुराने रास्ते के बाद fallback की तरह).
 *
 * दो धागे: एक टुकड़ा बनता है (synth, Dispatchers.Default) और उसी समय पिछला बजता है (AudioTrack, Dispatchers.IO).
 */
object St3Engine {
    private val lock = Mutex()
    @Volatile private var synth: St3Synth? = null
    private var style: St3Synth.Style? = null
    @Volatile private var player: St3Player? = null
    @Volatile private var stopFlag = false

    /** मॉडल खोलो (पहली बार ~1-3 सेकंड). null = सफल, वरना हिंदी में कारण */
    private fun ensure(c: Context): String? {
        if (!St3Store.coreReady(c)) return "आवाज़ का मुख्य मॉडल अभी डाउनलोड नहीं हुआ"
        val vid = St3Store.usableVoice(c) ?: return "कोई आवाज़ (F1-F5) अभी डाउनलोड नहीं हुई"
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

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** [text] बोलो. null = पूरा बोला (या रोका गया), वरना हिंदी में गलती */
    suspend fun say(c: Context, text: String): String? = lock.withLock {
        stopFlag = false
        val err = withContext(Dispatchers.Default) { ensure(c) }
        if (err != null) return@withLock err
        val s = synth ?: return@withLock "आवाज़ का मॉडल तैयार नहीं"
        val st = style ?: return@withLock "आवाज़ तैयार नहीं"
        val steps = St3Store.steps(c); val speed = St3Store.speed(c)
        val pieces = St3Text.chunk(text)
        val p = St3Player().also { player = it }
        val q = Channel<ByteArray>(capacity = 2)
        var failure: String? = null
        try {
            coroutineScope {
                launch(Dispatchers.Default) {
                    try {
                        pieces.forEachIndexed { i, t ->
                            if (stopFlag) return@forEachIndexed
                            val out = try { s.synth(t, st, steps, speed) { stopFlag } } catch (e: Throwable) {
                                Quiet.note(e); failure = "आवाज़ बनाते समय गलती (${e.message ?: e.javaClass.simpleName})"; null
                            } ?: return@forEachIndexed
                            if (i > 0) q.send(St3Synth.silence(300))
                            q.send(St3Synth.pcm16(out.wav))
                        }
                    } finally { q.close() }
                }
                launch(Dispatchers.IO) {
                    var total = 0L
                    for (b in q) {
                        if (!p.write(b)) break
                        total += b.size
                    }
                    p.drain(total)
                }
            }
        } finally { p.stop(); player = null }
        failure
    }

    /** अभी बोलना बंद (किसी भी धागे से) */
    fun stop() { stopFlag = true; player?.stop() }

    private fun releaseNow() {
        try { style?.close() } catch (ignored: Exception) {}; style = null
        try { synth?.close() } catch (ignored: Exception) {}; synth = null
    }

    /** RAM खाली करो (मॉडल हटाते समय) */
    suspend fun release() { stop(); lock.withLock { releaseNow() } }
}
