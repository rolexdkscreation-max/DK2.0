package com.dk.assistant.voice.st3

import android.content.Context
import com.dk.assistant.Quiet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * 19 नवंबर 2026: Supertonic 3 से फ़ोन पर ही बोलना.
 * - [synthBlocking]: एक टुकड़ा -> आवाज़ (44.1 kHz float). बातचीत वाला इंजन ([com.dk.assistant.voice.engine.St3VoiceEngine])
 *   और Settings का टेस्ट, दोनों यही बुलाते हैं. मॉडल एक बार खुलता है और खुला रहता है (एक समय में एक टुकड़ा - lock).
 * - [say]: Settings का "टेस्ट वाक्य" - दो coroutine: अगला टुकड़ा बनता है, उसी समय पिछला बजता है.
 * - [stop]: अभी जो बन रहा है वह तुरंत रुके (कदमों के बीच देखा जाता है).
 */
object St3Engine {
    /** fair = जो टुकड़ा पहले आया वही पहले बने (दूसरा टुकड़ा पहले वाले से आगे न निकले - पहली आवाज़ देर से न हो) */
    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    @Volatile private var synth: St3Synth? = null
    private var style: St3Synth.Style? = null
    @Volatile private var player: St3Player? = null
    /** हर [stop] पर बढ़ता है - पुराने काम अपने-आप रुकें */
    private val gen = AtomicInteger(0)
    @Volatile private var warming = false

    /** नापी हुई सबसे तेज़ गिनती; अभी नहीं नापी = 4 (या जितने core) */
    private fun threads(c: Context): Int = St3Store.threads(c).takeIf { it > 0 }
        ?: minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
    /** अभी खुले मॉडल के थ्रेड */
    @Volatile var openThreads = 0
        private set
    /** अभी खुला मॉडल XNNPACK पर */
    @Volatile var openXnn = false
        private set
    /** इस बार खुलने के बाद एक छोटा वाक्य बन चुका (वार्म-अप) */
    @Volatile private var warmedUp = false
    /** थ्रेड जाँच चल रही है (Settings में दिखे) */
    @Volatile var benching = false
        private set

    /** अभी खुला मॉडल तेज़ वाला ([St3Config.FAST]) */
    @Volatile var openFast = false
        private set

    /**
     * XNNPACK माँगा पर इस फ़ोन पर न खुले = सादा तरीका (आवाज़ बंद न हो).
     * 20 नवंबर 2026: तेज़ मॉडल ([St3Store.fastOn]) पहले; वह इस फ़ोन पर खुले ही नहीं = याद रखो ([St3Store.setFastBad])
     * और पुराना (अगर फ़ोन में है). कुछ नहीं मिटता.
     */
    private fun openBest(c: Context, n: Int, xnn: Boolean): St3Synth {
        val fast = St3Store.fastOn(c)
        try {
            return openFiles(St3Store.files(c, fast), n, xnn).also { openFast = fast }
        } catch (e: Throwable) {
            if (!fast) throw e
            Quiet.note(e)
            St3Store.setFastBad(c, true)
            com.dk.assistant.DKLog.d("ऑफ़लाइन आवाज़: तेज़ मॉडल इस फ़ोन पर नहीं खुला (${e.message?.take(80)}) - पुराना मॉडल")
            if (!St3Store.slowReady(c)) throw e
        }
        return openFiles(St3Store.files(c, false), n, xnn).also { openFast = false }
    }
    private fun openFiles(f: St3Synth.Files, n: Int, xnn: Boolean): St3Synth {
        if (xnn) {
            try { return St3Synth.open(f, n, xnn = true).also { openXnn = true } } catch (e: Throwable) { Quiet.note(e) }
        }
        openXnn = false
        return St3Synth.open(f, n)
    }

    /** lock के अंदर: मॉडल + आवाज़ तैयार. null = ठीक, वरना हिंदी कारण */
    private fun ensure(c: Context, wantVoice: String?): String? {
        if (!St3Store.coreReady(c)) return "आवाज़ का मुख्य मॉडल अभी डाउनलोड नहीं हुआ"
        val vid = wantVoice?.takeIf { St3Store.voiceReady(c, it) } ?: St3Store.usableVoice(c)
            ?: return "कोई आवाज़ (आवाज़ 1-5) अभी डाउनलोड नहीं हुई"
        // तेज़ मॉडल अभी-अभी डाउनलोड हुआ (या इस फ़ोन पर नहीं खुला) = अगली बार नया खोलो
        if (synth != null && openFast != St3Store.fastOn(c)) releaseNow()
        return try {
            val s = synth ?: threads(c).let { n -> openBest(c, n, St3Store.xnn(c)).also { synth = it; openThreads = n; warmedUp = false } }
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
    fun synthBlocking(c: Context, text: String, voice: String?, maxSteps: Int = 99, pick: ((Int) -> Int)? = null,
                      split: ((String, Int) -> List<String>)? = null, onPiece: (FloatArray) -> Unit) {
        val my = gen.get()
        val oldPrio = boost()
        try { synthLocked(c, text, voice, maxSteps, pick, split, onPiece, my) } finally { restore(oldPrio) }
    }

    private fun synthLocked(c: Context, text: String, voice: String?, maxSteps: Int, pick: ((Int) -> Int)?,
                            split: ((String, Int) -> List<String>)?, onPiece: (FloatArray) -> Unit, my: Int) {
        lock.withLock {
            if (gen.get() != my) return
            ensure(c, voice)?.let { throw IllegalStateException(it) }
            val s = synth ?: throw IllegalStateException("आवाज़ का मॉडल तैयार नहीं")
            val st = style ?: throw IllegalStateException("आवाज़ तैयार नहीं")
            // [pick]: बारी आने पर (lock मिलने के बाद) तय हो - तब पता है आगे कितनी आवाज़ तैयार रखी है
            val base = minOf(St3Store.steps(c), maxSteps)
            val steps = pick?.invoke(base)?.coerceIn(St3Config.STEPS_MIN, base) ?: base; val speed = St3Store.speed(c)
            // 20 नवंबर 2026 ([split]): लंबा टुकड़ा + आगे कम आवाज़ तैयार = अल्पविराम / "और, लेकिन" पर दो-तीन हिस्से (बारी पर तय)
            val pieces = St3Text.chunk(text).let { l -> if (split == null) l else l.flatMap { split(it, steps) } }
            for (piece in pieces) {
                if (gen.get() != my) return
                val t0 = System.currentTimeMillis()
                val out = s.synth(piece, st, steps, speed) { gen.get() != my } ?: continue
                if (gen.get() != my) return
                warmedUp = true
                // हर वाक्य: बनने का समय और आवाज़ की लंबाई (मालिक का नियम - धीमा हो तो दिखे)
                val row = St3Stats.Row(piece.length, System.currentTimeMillis() - t0, out.wav.size * 1000L / s.sampleRate, steps, openThreads,
                    openXnn, out.stageMs[2], out.stageMs[3])
                St3Stats.add(row)
                com.dk.assistant.DKLog.d(St3Stats.line(row))
                onPiece(out.wav)
            }
        }
    }

    /**
     * मॉडल पहले से खोल लो + एक छोटा वाक्य बनाकर गरम करो (पहला जवाब जल्दी). ऐप खुलते ही / बातचीत जुड़ते ही बुलाया जाता है.
     * इस फ़ोन पर थ्रेड अभी नहीं नापे = पहले 4-6 थ्रेड आज़माकर सबसे तेज़ चुनो (एक ही बार, ~10-20 s, पीछे).
     * सस्ता: खुला + गरम हो / चल रहा हो तो कुछ नहीं.
     */
    fun warm(c: Context) {
        if ((synth != null && warmedUp && St3Store.threads(c) > 0) || warming || !St3Store.ready(c)) return
        warming = true
        val app = c.applicationContext
        Thread({
            try {
                boost()                        // इस धागे पर खुले मॉडल के ORT/XNNPACK धागे भी यही प्राथमिकता लेते हैं
                if (St3Store.threads(app) <= 0) bench(app)
                lock.withLock {
                    if (ensure(app, St3Store.voice(app)) == null && !warmedUp) {
                        val t0 = System.currentTimeMillis()
                        synth?.let { s -> style?.let { st -> s.synth(WARM_LINE, st, 2, 1.05f) } }
                        warmedUp = true
                        com.dk.assistant.DKLog.d("ऑफ़लाइन आवाज़: मॉडल गरम (${if (openFast) "तेज़ मॉडल" else "पुराना मॉडल"}, ${openThreads} थ्रेड${if (openXnn) " XNN" else ""}, ${System.currentTimeMillis() - t0} ms, प्राथमिकता ${prio()})")
                    }
                }
            } catch (e: Throwable) { Quiet.note(e) } finally { warming = false }
        }, "dk-st3-warm").apply { isDaemon = true; start() }
    }

    /**
     * 20 नवंबर 2026 (मालिक का 3.335 लॉग): ChatGPT खुलते ही (DK पीछे गया) हर कदम 0.14 -> 0.20 (आवाज़ के हर सेकंड पर) =
     * ~40% धीमा, और ChatGPT सामने रहने तक वैसा ही. आवाज़ बनाने वाला धागा सामने वाले ऐप से CPU के लिए बराबरी पर लड़ता था.
     * अब बनाते समय धागा [SYNTH_PRIORITY] पर (Android: URGENT_DISPLAY = -8; माइक/speaker के -16/-19 से नीचे, यानी
     * सुनना-बजाना नहीं रुकता). मॉडल इसी प्राथमिकता वाले धागे पर खुलता है, तो उसके अपने (native) धागे भी यही लेते हैं.
     */
    const val SYNTH_PRIORITY = -8
    private fun prio(): Int = try { android.os.Process.getThreadPriority(android.os.Process.myTid()) } catch (e: Throwable) { Quiet.note(e); 0 }
    /** ऊँची प्राथमिकता; लौटाता है पुरानी (वापस रखने को) */
    private fun boost(): Int {
        val old = prio()
        if (old > SYNTH_PRIORITY) try { android.os.Process.setThreadPriority(SYNTH_PRIORITY) } catch (e: Throwable) { Quiet.note(e) }
        return old
    }
    private fun restore(old: Int) {
        if (old > SYNTH_PRIORITY) try { android.os.Process.setThreadPriority(old) } catch (e: Throwable) { Quiet.note(e) }
    }

    /** वार्म-अप का छोटा वाक्य (सुनाया नहीं जाता) */
    private const val WARM_LINE = "हाँ।"
    /** थ्रेड जाँच का वाक्य - आम जवाब जितना */
    private const val BENCH_LINE = "नमस्ते, मैं आपकी मदद के लिए तैयार हूँ।"

    /** 4-6 थ्रेड आज़माओ, सबसे तेज़ सेव करो. एक बार में एक ही मॉडल RAM में (कमज़ोर फ़ोन पर दो साथ भारी) */
    private fun bench(c: Context) {
        benching = true
        try {
            val times = LinkedHashMap<Int, Long>()
            val xTimes = LinkedHashMap<Int, Long>()
            val vid = St3Store.usableVoice(c) ?: return
            val combos = St3Stats.threadChoices(Runtime.getRuntime().availableProcessors()).flatMap { listOf(it to false, it to true) }
            for ((n, xnn) in combos) lock.withLock {
                releaseNow()
                // XNNPACK इस फ़ोन पर न खुले = बस वह जोड़ी छोड़ो
                val s = try { St3Synth.open(St3Store.files(c), n, xnn) } catch (e: Throwable) { Quiet.note(e); null } ?: return@withLock
                try {
                    val st = s.loadStyle(vid, com.dk.assistant.plus.models.ModelManager.file(c, St3Config.voice(vid).asset).readText())
                    try {
                        s.synth(WARM_LINE, st, 2, 1.05f)                          // पहला चलना हमेशा धीमा - गिनती में नहीं
                        var best = Long.MAX_VALUE
                        repeat(1) {                                                // 6 जोड़ियाँ - धीमे फ़ोन पर भी ~1 मिनट
                            val t0 = System.currentTimeMillis(); s.synth(BENCH_LINE, st, 5, 1.05f)
                            best = minOf(best, System.currentTimeMillis() - t0)
                        }
                        if (xnn) xTimes[n] = best else times[n] = best
                    } finally { st.close() }
                } finally { s.close() }
            }
            val plain = St3Stats.fastest(times)
            val fast = St3Stats.fastest(xTimes)
            // XNNPACK तभी जब सच में तेज़ (5% से ज़्यादा)
            val useX = fast != null && (plain == null || xTimes.getValue(fast) < times.getValue(plain) * 0.95)
            val pick = (if (useX) fast else plain) ?: return
            St3Store.setThreads(c, pick, useX)
            com.dk.assistant.DKLog.d("ऑफ़लाइन आवाज़: थ्रेड जाँच ${times.entries.joinToString { "${it.key} = ${it.value} ms" }}" +
                " • XNN ${xTimes.entries.joinToString { "${it.key} = ${it.value} ms" }.ifEmpty { "नहीं खुला" }} -> $pick${if (useX) " XNN" else ""} चुना")
        } catch (e: Throwable) {
            Quiet.note(e)
        } finally { benching = false }
    }

    /** Settings: "थ्रेड फिर से जाँचें" */
    fun rebench(c: Context) {
        St3Store.setThreads(c, 0)
        lock.withLock { releaseNow() }
        warm(c)
    }

    /** Settings का टेस्ट: [text] सीधे speaker पर. null = पूरा बोला (या रोका गया), वरना हिंदी में गलती */
    suspend fun say(c: Context, text: String): String? {
        stop()
        val my = gen.get()
        val askedAt = System.currentTimeMillis()
        val p = St3Player().also { player = it }
        val q = Channel<ByteArray>(capacity = 2)
        var failure: String? = null
        try {
            coroutineScope {
                launch(Dispatchers.Default) {
                    try {
                        var first = true
                        synthBlocking(c, text, St3Store.voice(c)) { wav ->
                            if (first) St3Stats.testFirstAudio(System.currentTimeMillis() - askedAt)
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
        openThreads = 0; warmedUp = false
    }

    /** RAM खाली करो (मॉडल हटाते समय) - रुककर, पीछे के धागे पर */
    fun release() { stop(); lock.withLock { releaseNow() } }
}
