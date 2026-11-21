package com.dk.assistant

import android.content.Context
import com.dk.assistant.stt.SherpaStt
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 21 नवंबर 2026 (मालिक: "DK Live का caption फ़ोन पर चलने वाले Sherpa-ONNX से, अलग key नहीं; माइक की आवाज़ एक साथ
 * Gemini Live और Sherpa-ONNX दोनों को, किसी की आवाज़ न कटे, Live पर कोई असर न पड़े"):
 * "आप:" वाली लाइन फ़ोन पर ही, बोलते-बोलते.
 *
 *  - [send] कभी नहीं रुकता: टुकड़ा सिर्फ़ एक छोटी कतार में जाता है (भरी = सबसे पुराना हटे). माइक का धागा और
 *    Gemini Live को जाने वाली आवाज़ पहले जैसी - पहचान अपने अलग, कम-ज़रूरी धागे पर.
 *  - वही एक मॉडल ([SherpaStt]) जो माइक बटन / अलग तरीका चलाते हैं - दो बार डाउनलोड नहीं.
 *  - मॉडल न हो / न खुले = [usable] false -> GeminiLive बैकअप ([LiveCaption], अगर caption key हो) या चेतावनी.
 *  - text के नियम [LiveCaption] जैसे: closeUtterance के 2 s तक आए शब्द = उसी बात के (देर से), फिर नई बात.
 */
class SherpaCaption(
    private val ctx: Context,
    /** (अभी की बात, इसमें पक्का text आया?) */
    private val onText: (String, Boolean) -> Unit,
    /** बंद हो चुकी बात का देर से आया text */
    private val onLate: (String) -> Unit,
    /** DK बोलते समय मालिक सच में बोल रहा है? (तब नया text = नई बात) */
    private val isNewSpeech: () -> Boolean = { false },
) : CaptionSource {
    companion object {
        private const val LATE_MS = 2000L
        /** 40 ms के टुकड़े: 150 = 6 s (मॉडल खुलने तक की आवाज़ भी न खोए) */
        private const val QMAX = 150
        private val END = FloatArray(0)
    }

    @Volatile override var ready = false
        private set
    @Volatile override var working = false
        private set
    @Volatile override var textAt = 0L
        private set
    @Volatile private var stopped = true
    @Volatile private var dead = false
    @Volatile private var worker: Thread? = null
    @Volatile private var endSent = true

    override val usable: Boolean get() = !dead && !stopped

    private val q = LinkedBlockingQueue<FloatArray>(QMAX)
    private val lock = Any()
    private val committed = StringBuilder()
    private var interim = ""
    private var fresh = true
    /** धारा के text का वह हिस्सा जो पिछली (बंद / फेंकी) बात का है */
    private var base = ""
    private var lastFull = ""
    private var closedCommitted = ""
    private var closedSeg = ""
    private var closedAt = 0L

    override fun text(): String = synchronized(lock) { if (fresh) "" else join(committed.toString(), interim) }

    override fun discard() {
        synchronized(lock) { committed.setLength(0); interim = ""; fresh = true; closedAt = 0L; base = lastFull }
    }

    override fun start() {
        if (!SherpaStt.ready(ctx)) { dead = true; DKLog.d("Caption: ${SherpaStt.whyNot(ctx)}"); return }
        if (!stopped) return
        stopped = false
        worker = Thread({ loop() }, "dk-caption-stt").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }

    override fun stop() {
        stopped = true
        ready = false
        q.clear()
        worker?.interrupt()
        worker = null
    }

    override fun closeUtterance(): String = synchronized(lock) {
        if (fresh) return@synchronized ""
        val t = join(committed.toString(), interim)
        closedCommitted = committed.toString().trim(); closedSeg = interim.trim()
        closedAt = System.currentTimeMillis()
        fresh = true
        t
    }

    override fun send(pcm: ByteArray, real: Boolean) {
        if (dead || stopped) return
        if (real) endSent = false
        val f = SherpaStt.toFloat(pcm, 0, pcm.size)
        if (!q.offer(f)) { q.poll(); q.offer(f) }      // भरी = पुरानी आवाज़ छोड़ो, माइक कभी न रुके
    }

    override fun endOfSpeech() {
        if (dead || stopped || endSent) return
        endSent = true
        if (!q.offer(END)) { q.poll(); q.offer(END) }
    }

    private fun join(a: String, b: String) = (a.trim() + " " + b.trim()).trim()

    private fun loop() {
        val s = SherpaStt.Stream.open(ctx)
        if (s == null) { dead = true; DKLog.d("Caption: ${SherpaStt.NAME} नहीं खुला - ${SherpaStt.whyNot(ctx).ifBlank { SherpaStt.problem }}"); return }
        ready = true
        DKLog.d("Caption: ${SherpaStt.NAME} तैयार (फ़ोन पर)")
        try {
            while (!stopped) {
                val f = try { q.poll(250, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { DKLog.d("Caption: ${e.message}"); null }
                if (stopped) break
                if (f == null) { tick(s); continue }
                val full = if (f === END) s.feed(FloatArray(SherpaStt.RATE * SherpaStt.TAIL_MS / 1000)) else s.feed(f)
                handle(s, full, f === END || s.endpoint())
            }
        } catch (e: Throwable) {
            dead = true
            DKLog.d("Caption: ${SherpaStt.NAME} रुका - ${e.javaClass.simpleName} ${e.message?.take(60)}")
        } finally {
            ready = false
            s.close()
        }
    }

    /** चुप्पी में भी देर वाली खिड़की खत्म हो तो पिछली बात अलग कर दो */
    private fun tick(s: SherpaStt.Stream) {
        synchronized(lock) {
            if (fresh && closedAt > 0 && System.currentTimeMillis() - closedAt >= LATE_MS) { base = lastFull; closedAt = 0L }
        }
        if (s.endpoint()) handle(s, s.text(), true)
    }

    private fun handle(s: SherpaStt.Stream, full: String, ep: Boolean) {
        var late: String? = null
        var fin = false
        val shown = synchronized(lock) {
            lastFull = full
            var seg = CaptionText.after(full, base)
            val now = System.currentTimeMillis()
            if (fresh) {
                if (closedAt > 0 && now - closedAt < LATE_MS && !isNewSpeech()) {
                    if (seg != closedSeg) { closedSeg = seg; late = join(closedCommitted, seg) }
                    if (ep) { closedCommitted = join(closedCommitted, seg); closedSeg = ""; resetLocked(s) }
                    return@synchronized ""
                }
                if (closedAt > 0) { base = full; closedAt = 0L; seg = "" }
                if (seg.isBlank()) {
                    if (ep) resetLocked(s)
                    return@synchronized ""
                }
                committed.setLength(0); interim = ""; fresh = false
            }
            if (ep && seg.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(seg.trim()); interim = ""; fin = true
                resetLocked(s)
            } else {
                interim = seg
                if (ep) resetLocked(s)
            }
            join(committed.toString(), interim)
        }
        val l = late
        if (l != null) {
            if (l.isNotBlank()) onLate(l)
            return
        }
        if (shown.isNotBlank()) { working = true; textAt = System.currentTimeMillis(); onText(shown, fin) }
    }

    /** धारा का पक्का हिस्सा पूरा - अगला खाली से (lock के अंदर, उसी धागे पर) */
    private fun resetLocked(s: SherpaStt.Stream) { s.reset(); base = ""; lastFull = "" }
}

/** pure (JUnit): धारा के पूरे text में से पिछली बात वाला शुरुआती हिस्सा हटाओ */
object CaptionText {
    fun after(full: String, base: String): String {
        if (base.isEmpty()) return full.trim()
        if (full.startsWith(base)) return full.substring(base.length).trim()
        var i = 0
        val n = minOf(full.length, base.length)
        while (i < n && full[i] == base[i]) i++
        // मॉडल ने पिछली बात का आख़िरी हिस्सा थोड़ा सुधारा - जहाँ तक मेल, उस शब्द के बाद से (शब्द बीच से न कटे)
        val sp = if (i == 0) -1 else full.lastIndexOf(' ', i - 1)
        return full.substring(if (sp < 0) 0 else sp).trim()
    }
}
