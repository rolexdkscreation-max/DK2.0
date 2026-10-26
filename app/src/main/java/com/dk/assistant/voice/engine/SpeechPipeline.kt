package com.dk.assistant.voice.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v3.68: तेज़ी की कड़ी (pipeline) - जवाब का text बहता हुआ आता है -> [TextChunker] पहला वाक्य/अल्पविराम
 * तक का टुकड़ा बनते ही काटता है -> उसी पल आवाज़ इंजन को -> आवाज़ कतार में -> एक ही AudioTrack पर
 * बिना रुके बजती है. जब तक एक टुकड़ा बजता है, अगले दो (2 धागे) बन रहे होते हैं.
 *
 * - जोड़ पर झटका नहीं: हर टुकड़े की शुरू/अंत की ख़ाली चुप्पी काटी जाती है, 5 ms fade-in, और
 *   विराम-चिह्न के हिसाब से छोटा, एक-सा ठहराव (। = 140 ms, , = 60 ms).
 * - इंजन फ़ेल: वही टुकड़ा Gemini TTS से; वह भी फ़ेल = फ़ोन की अपनी आवाज़ (ऐप चुप/अटके नहीं).
 * - [stop] (user बोलने लगा): कतार, बनते टुकड़े और बजती आवाज़ - सब उसी पल बंद.
 */
class SpeechPipeline(private val ctx: Context, private val hooks: Hooks) {

    interface Hooks {
        /** AudioAttributes usage (speaker / Bluetooth / कॉल) */
        fun usage(): Int
        fun log(m: String)
        /** यह आवाज़ अभी speaker पर जा रही है (गूँज-पहचान, "बोल रहा है" का समय) */
        fun onPlay(pcm: ByteArray, now: Long)
        /** इस बारी की पहली आवाज़ बजी */
        fun onFirstAudio(at: Long, engineId: String)
        fun onSpeaking(on: Boolean)
        /** अब तक बोला गया text (स्क्रीन पर साथ-साथ) */
        fun onSpoken(soFar: String)
        /** v3.74: अब इस्तेमाल नहीं होता - फ़ोन की अपनी (Android) आवाज़ कभी नहीं बोलती */
        suspend fun phoneSay(text: String)
        fun phoneStop()
    }

    private class Seg(val text: String, val gen: Int, val createdAt: Long) {
        val q = LinkedBlockingQueue<ByteArray>()
        @Volatile var engine = ""
        @Volatile var phone = false
        @Volatile var firstPcmAt = 0L
    }

    private val end = ByteArray(0)
    private val segs = LinkedBlockingQueue<Seg>()
    private val pending = AtomicInteger(0)
    private val synth = Executors.newFixedThreadPool(2) { r -> Thread(r, "dk-tts-synth").apply { isDaemon = true } }
    private val chunker = TextChunker()
    @Volatile private var gen = 0
    @Volatile private var engine: VoiceEngine? = null
    @Volatile private var firstPending = false
    @Volatile private var turnAt = 0L
    @Volatile private var lastFeedAt = 0L
    @Volatile private var playUntil = 0L
    @Volatile private var speaking = false
    @Volatile private var released = false
    @Volatile private var lastWarmAt = 0L
    /** v3.71: इस बारी के पहले वाक्य की आवाज़ इंजन से कब बनकर आई (देरी का हिसाब) */
    @Volatile var firstSegPcmAt = 0L
        private set
    private val spoken = StringBuilder()
    @Volatile private var track: AudioTrack? = null
    private var trackUsage = -1
    private val player: Thread

    init {
        player = Thread({ loop() }, "dk-tts-play").apply { isDaemon = true; start() }
    }

    /** कुछ बन रहा / कतार में / बज रहा है */
    val busy: Boolean get() = pending.get() > 0 || System.currentTimeMillis() < playUntil + 120

    /** इंजन का कनेक्शन गर्म रखो (हर 20 s में ज़्यादा से ज़्यादा एक बार) */
    fun warm(e: VoiceEngine? = engine ?: VoiceEngines.active(ctx)) {
        val now = System.currentTimeMillis()
        if (e == null || now - lastWarmAt < 20_000) return
        lastWarmAt = now
        Thread({ try { e.warm(ctx) } catch (_: Exception) {} }, "dk-tts-warm").apply { isDaemon = true; start() }
    }

    /** नया जवाब शुरू */
    fun begin(e: VoiceEngine) {
        engine = e
        synchronized(chunker) { chunker.reset() }
        synchronized(spoken) { spoken.setLength(0) }
        firstPending = true
        turnAt = System.currentTimeMillis(); lastFeedAt = turnAt
    }

    /** जवाब का अगला text (जैसे-जैसे आए) */
    fun feed(t: String) {
        if (released || t.isEmpty()) return
        lastFeedAt = System.currentTimeMillis()
        val pieces = synchronized(chunker) { chunker.feed(t) }
        for (p in pieces) enqueue(p)
    }

    /** जवाब पूरा लिखा गया - बचा text आख़िरी टुकड़ा */
    fun endTurn() {
        val rest = synchronized(chunker) { chunker.flush() }
        if (rest != null) enqueue(rest)
    }

    /** user बोलने लगा / टोका: सब तुरंत चुप */
    fun stop() {
        gen++
        synchronized(chunker) { chunker.reset() }
        val dropped = ArrayList<Seg>(); segs.drainTo(dropped)
        if (dropped.isNotEmpty()) pending.addAndGet(-dropped.size)
        try { engine?.stop() } catch (_: Exception) {}
        VoiceEngines.fallback()?.let { if (it !== engine) try { it.stop() } catch (_: Exception) {} }
        try { hooks.phoneStop() } catch (_: Exception) {}
        track?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
        playUntil = 0L; firstPending = false
        VoiceLevel.clear()
    }

    fun release() {
        released = true
        stop()
        player.interrupt()
        synth.shutdownNow()
        track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        track = null
    }

    // ------------------------------------------------------------------ बनाना
    private fun enqueue(text: String) {
        val e = engine ?: return
        val s = Seg(text, gen, System.currentTimeMillis())
        pending.incrementAndGet()
        segs.put(s)
        try { synth.execute { make(s, e) } } catch (_: Exception) { s.phone = true; s.q.put(end) }
    }

    private fun make(s: Seg, chosen: VoiceEngine) {
        val chain = buildList {
            add(chosen)
            VoiceEngines.fallback()?.let { if (it.id != chosen.id) add(it) }
        }
        var ok = false
        for ((i, e) in chain.withIndex()) {
            if (s.gen != gen) break
            val last = i == chain.lastIndex
            if (!last && VoiceEngines.isFailed(e.id)) continue          // अभी-अभी टूटा था - सीधा अगला
            val why = e.notReady(ctx)
            if (why != null) { VoiceEngines.markFailed(e.id, why); continue }     // जैसे Worker का पता नहीं - अगला
            try {
                var got = false
                e.speak(ctx, s.text, e.voice(ctx), PcmOut { b ->
                    if (s.gen == gen && b.isNotEmpty()) {
                        if (s.firstPcmAt == 0L) s.firstPcmAt = System.currentTimeMillis()
                        got = true; s.q.put(b)
                    }
                })
                if (s.gen != gen) break
                if (!got) throw IllegalStateException("कोई आवाज़ नहीं आई")
                s.engine = e.id; ok = true
                if (e.id != chosen.id) hooks.log("आवाज़ इंजन: ${chosen.title} नहीं चला - यह टुकड़ा ${e.title} से")
                break
            } catch (ex: Exception) {
                if (s.gen != gen) break
                VoiceEngines.markFailed(e.id, ex.message ?: ex.javaClass.simpleName)
                hooks.log("आवाज़ इंजन ${e.title} में दिक़्क़त: ${(ex.message ?: ex.javaClass.simpleName).take(90)}")
                // आधा बज चुका हो तो दोबारा शुरू से नहीं (वही शब्द दो बार) - जितना बना उतना
                if (s.firstPcmAt > 0) { s.engine = e.id; ok = true; break }
            }
        }
        if (!ok && s.gen == gen) { s.phone = true; hooks.log("आवाज़ इंजन: कोई नहीं चला - यह टुकड़ा चुप (सिर्फ़ लिखा), फ़ोन की आवाज़ नहीं") }
        s.q.put(end)
    }

    // ------------------------------------------------------------------ बजाना
    private fun loop() {
        while (!released) {
            val s = try { segs.poll(30, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            stallCheck()
            if (s == null) { idleCheck(); continue }
            try { if (s.gen == gen) play(s) } catch (e: Exception) { hooks.log("आवाज़ बजाने में गलती: ${e.message}") }
            pending.decrementAndGet()
        }
    }

    /** पहला टुकड़ा बना ही नहीं और text 250 ms से रुका है - जितना है (≥3 शब्द) अभी बोल दो */
    private fun stallCheck() {
        if (!firstPending || System.currentTimeMillis() - lastFeedAt < 250) return
        val p = synchronized(chunker) { chunker.flushFirstIfStalled(3) } ?: return
        enqueue(p)
    }

    private fun idleCheck() {
        if (speaking && pending.get() == 0 && System.currentTimeMillis() > playUntil + 150) {
            speaking = false; hooks.onSpeaking(false)
        }
    }

    private fun ensureTrack(): AudioTrack {
        val u = hooks.usage()
        track?.let { if (trackUsage == u) return it }
        track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        val min = AudioTrack.getMinBufferSize(VoiceEngine.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoiceEngine.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, VoiceEngine.RATE * 2 / 5))     // ~200 ms: जल्दी शुरू, अटके बिना
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play()
        track = t; trackUsage = u
        return t
    }

    private fun play(s: Seg) {
        val g = s.gen
        if (s.phone || waitPhone(s)) {
            // v3.74: Gemini/इंजन की आवाज़ नहीं बनी -> फ़ोन TTS नहीं, बस text स्क्रीन पर (चुप)
            noteSpoken(s.text)
            return
        }
        val tr = ensureTrack()
        val shape = Joint()
        val even = PcmEven()
        var announced = false
        val deadline = System.currentTimeMillis() + 12_000
        while (g == gen) {
            val b = s.q.poll(40, TimeUnit.MILLISECONDS)
            if (b == null) { if (System.currentTimeMillis() > deadline) break; continue }
            if (b === end) break
            val p = even.take(b) ?: continue
            for (piece in shape.push(p)) {
                if (!announced) { announced = true; noteSpoken(s.text) }
                write(tr, piece, g, s)
            }
        }
        if (g != gen) return
        for (piece in shape.finish(pauseMs(s.text))) write(tr, piece, g, s)
    }

    /** इंजन फ़ेल होकर phone पर आया हो तो END तक रुककर पता चलता है */
    private fun waitPhone(s: Seg): Boolean {
        val t = System.currentTimeMillis() + 12_000
        while (s.gen == gen && System.currentTimeMillis() < t) {
            if (s.phone) return true
            if (s.q.isNotEmpty()) return s.phone
            Thread.sleep(10)
        }
        return s.phone
    }

    private fun write(tr: AudioTrack, pcm: ByteArray, g: Int, s: Seg) {
        if (pcm.isEmpty() || g != gen) return
        val now = System.currentTimeMillis()
        markSpeaking()
        if (firstPending) {
            firstPending = false
            firstSegPcmAt = if (s.firstPcmAt > 0) s.firstPcmAt else now
            hooks.onFirstAudio(now, s.engine.ifBlank { engine?.id ?: "" })
            hooks.log("पहला टुकड़ा: \"${s.text.take(40)}\" • text आने के ${s.createdAt - turnAt} ms बाद कटा • " +
                "इंजन ने ${if (s.firstPcmAt > 0) s.firstPcmAt - s.createdAt else -1} ms में पहली आवाज़ दी")
        }
        val startAt = maxOf(playUntil, now)
        playUntil = startAt + pcm.size * 1000L / (VoiceEngine.RATE * 2)
        VoiceLevel.schedule(pcm, startAt)
        hooks.onPlay(pcm, now)
        var off = 0
        while (off < pcm.size && g == gen) {
            val n = try { tr.write(pcm, off, pcm.size - off) } catch (_: Exception) { -1 }
            if (n <= 0) break
            off += n
        }
    }

    private fun markSpeaking() { if (!speaking) { speaking = true; hooks.onSpeaking(true) } }

    private fun noteSpoken(t: String) {
        val all = synchronized(spoken) { if (spoken.isNotEmpty()) spoken.append(' '); spoken.append(t); spoken.toString() }
        hooks.onSpoken(all)
    }

    companion object {
        /** टुकड़े के बाद ठहराव - हर बार एक-सा (आवाज़ की लय बनी रहे) */
        fun pauseMs(t: String): Int {
            val c = t.trimEnd().trimEnd(')', '"', '\'', '”', '’').lastOrNull() ?: return 0
            return when (c) { '।', '.', '!', '?', '…', '॥' -> 140; ',', ';', ':', '—', '–' -> 60; else -> 20 }
        }
    }
}

/**
 * टुकड़ों के जोड़: शुरू की चुप्पी काटो (15 ms छोड़कर) + 5 ms fade-in; बीच की चुप्पी जस की तस;
 * आख़िर की चुप्पी काटकर 40 ms + तय ठहराव. आवाज़ आते ही आगे भेजता है - कोई देरी नहीं जोड़ता.
 * (Android के बिना - JVM test)
 */
class Joint(private val rate: Int = VoiceEngine.RATE, private val thr: Int = 380) {
    private var started = false
    private val held = java.io.ByteArrayOutputStream()

    fun push(p: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var b = p
        if (!started) {
            val i = firstLoud(b)
            if (i < 0) return out                                    // अभी सिर्फ़ चुप्पी
            val back = (rate * 15 / 1000) * 2
            b = b.copyOfRange(maxOf(0, i - back), b.size)
            fadeIn(b, rate * 5 / 1000)
            started = true
        }
        val last = lastLoud(b)
        if (last < 0) { held.write(b); return out }
        if (held.size() > 0) { out.add(held.toByteArray()); held.reset() }
        val cut = last + 2
        out.add(if (cut >= b.size) b else b.copyOfRange(0, cut))
        if (cut < b.size) held.write(b, cut, b.size - cut)
        return out
    }

    /** टुकड़ा ख़त्म: आख़िरी चुप्पी में से 40 ms + ठहराव */
    fun finish(pauseMs: Int): List<ByteArray> {
        if (!started) return emptyList()
        val keep = minOf(held.size(), (rate * 40 / 1000) * 2)
        val tail = ByteArray(keep + (rate * pauseMs / 1000) * 2)
        if (keep > 0) System.arraycopy(held.toByteArray(), 0, tail, 0, keep)
        held.reset()
        return listOf(tail)
    }

    private fun s(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
    private fun firstLoud(b: ByteArray): Int { var i = 0; while (i + 1 < b.size) { if (kotlin.math.abs(s(b, i)) > thr) return i; i += 2 }; return -1 }
    private fun lastLoud(b: ByteArray): Int { var i = (b.size and 1.inv()) - 2; while (i >= 0) { if (kotlin.math.abs(s(b, i)) > thr) return i; i -= 2 }; return -1 }
    private fun fadeIn(b: ByteArray, n: Int) {
        var k = 0; var i = 0
        while (i + 1 < b.size && k < n) {
            val v = s(b, i) * k / n
            b[i] = (v and 0xff).toByte(); b[i + 1] = ((v shr 8) and 0xff).toByte()
            k++; i += 2
        }
    }
}
