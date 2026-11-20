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
 * - इंजन फ़ेल: वही टुकड़ा Gemini TTS से; वह भी फ़ेल = DK की ऑफ़लाइन आवाज़ (St3, 20 नवंबर 2026); वह भी नहीं = चुप/Live की आवाज़.
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
        /** v3.7: कोई इंजन आवाज़ नहीं बना पाया - यह टुकड़ा सिर्फ़ लिखा गया (Live अपनी आवाज़ पर चला जाए) */
        fun onSilent(text: String) {}
        /** चुना हुआ इंजन (जैसे Gemini TTS/Worker) नहीं चला - साफ़ कारण स्क्रीन पर */
        fun onEngineError(title: String, why: String) {}
        /**
         * नवंबर 2026 (SplitRescue): यह टुकड़ा (जवाब की लिखावट के [at] अक्षर से) Gemini TTS नहीं बोल पाया / अटका.
         * true = Live की अपनी आवाज़ ने बचा जवाब ले लिया -> यह pipeline इस जवाब में चुप ([handOff]).
         */
        fun takeOver(at: Int, why: String): Boolean = false
    }

    private class Seg(val text: String, val gen: Int, val createdAt: Long, val at: Int = 0) {
        val q = LinkedBlockingQueue<ByteArray>()
        @Volatile var engine = ""
        @Volatile var phone = false
        @Volatile var firstPcmAt = 0L
    }

    private val end = ByteArray(0)
    private val segs = LinkedBlockingQueue<Seg>()
    private val pending = AtomicInteger(0)
    private val synth = Executors.newFixedThreadPool(2) { r -> Thread(r, "dk-tts-synth").apply { isDaemon = true } }
    /**
     * 20 नवंबर 2026 (मालिक का लॉग: 14:24 के बाद बनना बजने से धीमा): फ़ोन वाली आवाज़ (St3) का अपना एक धागा, शुरू से ऊँची
     * प्राथमिकता ([St3Engine.SYNTH_PRIORITY], माइक/speaker से नीचे) पर - बाकी इंजनों के धागों से अलग, क्रम से एक-एक टुकड़ा.
     */
    private val st3Synth = Executors.newSingleThreadExecutor { r ->
        Thread({
            try { android.os.Process.setThreadPriority(com.dk.assistant.voice.st3.St3Engine.SYNTH_PRIORITY) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
            r.run()
        }, "dk-st3-synth").apply { isDaemon = true }
    }
    /** इस जवाब में ऑफ़लाइन आवाज़ धीमी होकर Gemini की आवाज़ पर गए - लॉग एक बार */
    @Volatile private var geminiNoted = false
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
    /** नवंबर 2026: इस जवाब का बाकी हिस्सा Live की अपनी आवाज़ बोल रही है - नया text नहीं लेना */
    @Volatile private var handed = false
    /** इस जवाब का पूरा text आ चुका ([endTurn]) */
    @Volatile private var ended = false
    /** इस जवाब में अब तक टुकड़ों का कितना text (अक्षर) - Live आवाज़ पर जाएँ तो कहाँ से */
    @Volatile private var segAt = 0
    /** v3.71: इस बारी के पहले वाक्य की आवाज़ इंजन से कब बनकर आई (देरी का हिसाब) */
    @Volatile var firstSegPcmAt = 0L
        private set
    private val spoken = StringBuilder()
    @Volatile private var track: AudioTrack? = null
    private var trackUsage = -1
    /** v3.80: इस track पर लगी गति/पिच का version ([VoiceTune]) */
    @Volatile private var tuneVer = -1
    private val player: Thread

    init {
        player = Thread({ loop() }, "dk-tts-play").apply { isDaemon = true; start() }
        // 19 नवंबर 2026: बजते समय भी देखो - अगला टुकड़ा समय पर बनना शुरू हो (सिर्फ़ refillMs वाले इंजन, जैसे St3)
        Thread({ while (!released) { try { Thread.sleep(40) } catch (_: InterruptedException) { break }; refillCheck() } },
            "dk-tts-refill").apply { isDaemon = true; start() }
        synchronized(alive) { alive[this] = true }
    }

    /** कुछ बन रहा / कतार में / बज रहा है */
    val busy: Boolean get() = pending.get() > 0 || System.currentTimeMillis() < playUntil + 120

    /**
     * नवंबर 2026 (मालिक: "TTS चालू हो तो ठीक से सुनता नहीं"): सच में आवाज़ बज रही है (या जवाब के बीच का छोटा
     * ठहराव). [busy] टुकड़ा "बन रहा" होने पर भी true था - तब कुछ बजता नहीं, फिर भी माइक आपकी आवाज़ रोकता था.
     * जवाब की पहली आवाज़ से पहले = false (माइक खुला, जैसे सीधी Live आवाज़ में).
     */
    val talking: Boolean get() {
        val now = System.currentTimeMillis()
        return now < playUntil + 120 || (!firstPending && pending.get() > 0 && now < playUntil + GAP_HOLD_MS)
    }

    /** इस जवाब की पहली आवाज़ बज चुकी (या जवाब शुरू ही नहीं हुआ) */
    val audioStarted: Boolean get() = !firstPending

    /** इंजन का कनेक्शन गर्म रखो (हर 20 s में ज़्यादा से ज़्यादा एक बार) */
    fun warm(e: VoiceEngine? = engine ?: VoiceEngines.active(ctx)) {
        val now = System.currentTimeMillis()
        if (e == null || now - lastWarmAt < 20_000) return
        lastWarmAt = now
        Thread({ try { e.warm(ctx) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }, "dk-tts-warm").apply { isDaemon = true; start() }
    }

    /** नया जवाब शुरू */
    fun begin(e: VoiceEngine) {
        engine = e
        // 20 नवंबर 2026: पिछले जवाब का सार (बनी/आवाज़ अनुपात, पहली आवाज़, तरीका, फ़ोन की गर्मी) - पहले/बाद मिलाने को
        if (e.id == St3VoiceEngine.ID) com.dk.assistant.voice.st3.St3Load.newAnswer(com.dk.assistant.voice.st3.St3Stats.lastFirstMs)?.let { hooks.log(it) }
        geminiNoted = false
        if (e.id == St3VoiceEngine.ID) St3VoiceEngine.newAnswer()     // 19 नवंबर 2026: पहला टुकड़ा जल्दी
        if (e.id == St3VoiceEngine.ID) St3VoiceEngine.lead = { leadMs() }   // बाकी टुकड़ों के कदम: आगे कितनी आवाज़ तैयार
        synchronized(chunker) { chunker.reset(profileFor(e.id)) }
        synchronized(spoken) { spoken.setLength(0) }
        firstPending = true
        handed = false; segAt = 0; ended = false
        turnAt = System.currentTimeMillis(); lastFeedAt = turnAt
    }

    /** जवाब का अगला text (जैसे-जैसे आए) */
    fun feed(t: String) {
        if (released || handed || t.isEmpty()) return
        lastFeedAt = System.currentTimeMillis()
        val pieces = synchronized(chunker) { chunker.feed(t) }
        for (p in pieces) enqueue(p)
    }

    /** जवाब पूरा लिखा गया - बचा text आख़िरी टुकड़ा */
    fun endTurn() {
        ended = true
        if (handed) return
        val rest = synchronized(chunker) { chunker.flush() }
        if (rest != null) enqueue(rest)
    }

    /** user बोलने लगा / टोका: सब तुरंत चुप */
    fun stop() {
        gen++
        synchronized(chunker) { chunker.reset() }
        val dropped = ArrayList<Seg>(); segs.drainTo(dropped)
        if (dropped.isNotEmpty()) pending.addAndGet(-dropped.size)
        try { engine?.stop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        VoiceEngines.fallbacks().forEach { if (it !== engine) try { it.stop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        try { hooks.phoneStop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        track?.let { try { it.pause(); it.flush(); it.play() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        playUntil = 0L; firstPending = false
        VoiceLevel.clear()
    }

    /** Live की अपनी आवाज़ ने यह जवाब ले लिया: अभी चुप, और इस जवाब का आगे का text भी नहीं (अगले [begin] तक) */
    fun handOff() {
        handed = true
        stop()
    }

    fun release() {
        released = true
        stop()
        player.interrupt()
        synth.shutdownNow()
        st3Synth.shutdownNow()
        track?.let { try { it.stop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; try { it.release() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        track = null
    }

    // ------------------------------------------------------------------ बनाना
    private fun enqueue(text: String) {
        val e = engine ?: return
        if (handed) return
        val s = Seg(text, gen, System.currentTimeMillis(), segAt)
        segAt += text.length + 1
        pending.incrementAndGet()
        segs.put(s)
        val ex = if (e.id == St3VoiceEngine.ID) st3Synth else synth
        try { ex.execute { make(s, e) } } catch (_: Exception) { s.phone = true; s.q.put(end) }
    }

    private fun make(s: Seg, chosen: VoiceEngine) {
        val all = buildList {
            add(chosen)
            addAll(VoiceEngines.fallbacks(chosen.id))   // 20 नवंबर 2026: Gemini TTS, फिर ऑफ़लाइन आवाज़ (St3)
        }
        // 20 नवंबर 2026 (मालिक): ऑफ़लाइन आवाज़ हल्के तरीके में भी लगातार धीमी ([com.dk.assistant.voice.st3.St3Load]) = इस जवाब का बाकी Gemini की आवाज़ से
        val toGemini = chosen.id == St3VoiceEngine.ID && com.dk.assistant.voice.st3.St3Load.toGemini
        val chain = if (toGemini) all.sortedBy { if (it.id == VoiceEngines.GEMINI_TTS) 0 else 1 } else all
        if (toGemini && !geminiNoted) { geminiNoted = true; hooks.log("आवाज़ इंजन: ऑफ़लाइन आवाज़ लगातार धीमी - इस जवाब का बाकी Gemini की आवाज़ से (Gemini न चले तो फिर ऑफ़लाइन)") }
        if (chosen.id == St3VoiceEngine.ID) com.dk.assistant.voice.st3.St3Load.mark(System.currentTimeMillis() + BUSY_HOLD_MS)
        var ok = false
        for ((i, e) in chain.withIndex()) {
            if (s.gen != gen) break
            val last = i == chain.lastIndex
            // 19 नवंबर 2026: फ़ोन वाली आवाज़ (St3) एक गलती पर 2 मिनट नहीं छोड़ी जाती - हर टुकड़े पर फिर कोशिश (गलती हो तो तुरंत अगला)
            if (!last && e.id != St3VoiceEngine.ID && VoiceEngines.isFailed(e.id)) continue          // अभी-अभी टूटा था - सीधा अगला
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
                if (e.id != chosen.id && !toGemini) hooks.log("आवाज़ इंजन: ${chosen.title} नहीं चला - यह टुकड़ा ${e.title} से")
                break
            } catch (ex: Exception) {
                if (s.gen != gen) break
                VoiceEngines.markFailed(e.id, ex.message ?: ex.javaClass.simpleName)
                hooks.log("आवाज़ इंजन ${e.title} में दिक़्क़त: ${(ex.message ?: ex.javaClass.simpleName).take(90)}")
                if (e.id == chosen.id) try { hooks.onEngineError(e.title, ex.message ?: ex.javaClass.simpleName) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
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
            playing = s
            try { if (s.gen == gen) play(s) } catch (e: Exception) { hooks.log("आवाज़ बजाने में गलती: ${e.message}") }
            playing = null
            pending.decrementAndGet()
        }
    }

    /** पहला टुकड़ा बना ही नहीं और text रुका है (फ़ोन वाली आवाज़ 150 ms / बाकी 250 ms) - जितना है (≥2 / ≥3 शब्द) अभी बोल दो */
    private fun stallCheck() {
        val pr = chunker.profile
        if (!firstPending || System.currentTimeMillis() - lastFeedAt < pr.stallMs) return
        val p = synchronized(chunker) { chunker.flushFirstIfStalled(pr.stallMin) } ?: return
        enqueue(p)
    }

    /**
     * 19 नवंबर 2026 (मालिक: "एक वाक्य बोलता है, फिर रुकता है"): जड़ - अगला टुकड़ा तभी बनता था जब उसका पूरा वाक्य
     * (। ? !) आ जाए; Live की लिखावट लगभग बोलने की रफ़्तार से आती है, तो पिछला ख़त्म होते-होते ही अगला बनना शुरू होता
     * = हर वाक्य के बीच बनने जितनी चुप्पी. अब: पिछला ख़त्म होने वाला है और अगला कुछ नहीं बन रहा -> जितना text है अभी.
     */
    private fun refillCheck() {
        val pr = chunker.profile
        if (pr.refillMs <= 0 || firstPending || handed || released) return
        if (segs.isNotEmpty() || pending.get() > 1) return          // अगला पहले से बन रहा / कतार में
        if (playUntil - System.currentTimeMillis() > pr.refillMs) return
        val p = synchronized(chunker) { chunker.flushIfAtLeast(pr.refillMin) } ?: return
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
        track?.let { try { it.stop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; try { it.release() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        val min = AudioTrack.getMinBufferSize(VoiceEngine.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoiceEngine.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, VoiceEngine.RATE * 2 / 5))     // ~200 ms: जल्दी शुरू, अटके बिना
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        VoiceTune.load(ctx)
        tuneVer = VoiceTune.apply(t, -1)
        t.play()
        track = t; trackUsage = u
        return t
    }

    private fun play(s: Seg) {
        val g = s.gen
        if (s.phone || waitPhone(s)) {
            if (g != gen) return
            // नवंबर 2026: आवाज़ नहीं बनी -> पहले Live की अपनी आवाज़ (वही जवाब, वही आवाज़) - चुप नहीं
            if (rescue(s, SplitRescueWhy.FAILED, 0L)) return
            // v3.74: Gemini/इंजन की आवाज़ नहीं बनी -> फ़ोन TTS नहीं, बस text स्क्रीन पर (चुप)
            noteSpoken(s.text)
            try { hooks.onSilent(s.text) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            return
        }
        if (firstPending && engine?.id == St3VoiceEngine.ID) preroll(s)
        if (g != gen) return
        val tr = ensureTrack()
        val prevEnd = playUntil
        val wasFirst = firstPending
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
                if (!announced) { announced = true; noteSpoken(s.text); showVoice(s); if (!wasFirst) noteGap(s, prevEnd) }
                write(tr, piece, g, s)
            }
        }
        if (g != gen) return
        // 20 नवंबर 2026 (मालिक: "ठहराव नहीं दिखता"): फ़ोन वाली आवाज़ में इंसान जैसे अलग-अलग ठहराव ([St3Prosody.pauseMs])
        val rest = if (engine?.id == St3VoiceEngine.ID && com.dk.assistant.voice.st3.St3Store.natural(ctx)) com.dk.assistant.voice.st3.St3Prosody.pauseMs(s.text) else pauseMs(s.text)
        for (piece in shape.finish(rest)) write(tr, piece, g, s)
        // 20 नवंबर 2026: ऑफ़लाइन आवाज़ बज रही है - तब तक पीछे के भारी काम रुकें ([com.dk.assistant.voice.st3.St3Load.busy])
        if (engine?.id == St3VoiceEngine.ID) com.dk.assistant.voice.st3.St3Load.mark(playUntil + BUSY_HOLD_MS)
    }

    /**
     * 19 नवंबर 2026 (मालिक: "पहला वाक्य बोलकर 1-2 सेकंड रुकता है, फिर लगातार"): फ़ोन वाली आवाज़ (St3) का पहला टुकड़ा
     * बन गया - बजाने से पहले ज़्यादा से ज़्यादा [PREROLL_MS] देखो कि दूसरा भी बन जाए (कतार में एक आगे तैयार). छोटा-सा
     * इंतज़ार शुरू में, ताकि पहले वाक्य के बाद चुप्पी न हो. पहला टुकड़ा लंबा हो / जवाब इतना ही हो = तुरंत.
     */
    private fun preroll(s: Seg) {
        if (pcmMs(s) >= PREROLL_SKIP_MS) return
        val t0 = System.currentTimeMillis()
        var asked = false
        // 20 नवंबर 2026 (मालिक: "बजना तभी शुरू करो जब कम से कम 1.5-2 सेकंड की आवाज़ तैयार हो"): पहले दूसरे टुकड़े का
        // ज़रा-सा हिस्सा बनते ही बजना शुरू होता था - धीमे पल में फिर भी 1.4-3 s चुप्पी. अब [PREROLL_READY_MS] तैयार हो
        // (या [PREROLL_WAIT_MS] बीत जाए / इससे ज़्यादा आवाज़ आनी ही नहीं) तब शुरू.
        while (s.gen == gen && System.currentTimeMillis() - t0 < PREROLL_WAIT_MS) {
            val next = segs.peek()
            if (next != null && next.phone) return
            if (readyMs(s) >= PREROLL_READY_MS) return                   // काफ़ी आवाज़ तैयार
            if (next == null && ended) {                       // पूरा जवाब आ चुका: बचा text आख़िरी टुकड़ा, नहीं बचा = यही अकेला
                val rest = synchronized(chunker) { chunker.flush() }
                if (rest == null && s.q.contains(end)) return        // यही अकेला, पूरा बन चुका
                if (rest != null) enqueue(rest)
            }
            if (next == null && !asked && System.currentTimeMillis() - t0 > 100) {
                asked = true                                       // अगला वाक्य अधूरा - जितना है (3+ शब्द) अभी बनाओ
                synchronized(chunker) { chunker.flushIfAtLeast(chunker.profile.refillMin) }?.let { enqueue(it) }
            }
            Thread.sleep(10)
        }
    }

    /** अभी बज रहा टुकड़ा ([lead] का हिसाब) */
    @Volatile private var playing: Seg? = null

    /** अभी कितने ms की आवाज़ बजने को तैयार: speaker पर बाकी + बजते टुकड़े का बचा + कतार में बने टुकड़े */
    fun leadMs(): Long {
        val now = System.currentTimeMillis()
        var ms = maxOf(0L, playUntil - now)
        playing?.let { if (it.gen == gen) ms += pcmMs(it) }
        for (x in segs) if (x.gen == gen && x.q.isNotEmpty()) ms += pcmMs(x)
        return ms
    }

    /** बजने को तैयार: यह टुकड़ा + कतार में अगले टुकड़ों का बना हिस्सा (ms) */
    private fun readyMs(s: Seg): Long {
        var ms = pcmMs(s)
        for (x in segs) if (x.gen == s.gen) ms += pcmMs(x)
        return ms
    }

    /** कतार में रखी (बनी हुई) आवाज़ कितने ms की */
    private fun pcmMs(s: Seg): Long = s.q.sumOf { it.size.toLong() } * 1000L / (VoiceEngine.RATE * 2L)

    /** दो टुकड़ों के बीच चुप्पी हुई = लॉग में कारण (बनने में देर या text देर से) */
    private fun noteGap(s: Seg, prevEnd: Long) {
        if (prevEnd <= 0) return
        val gap = System.currentTimeMillis() - prevEnd
        if (gap < GAP_LOG_MS) return
        val build = if (s.firstPcmAt > 0) s.firstPcmAt - s.createdAt else -1
        val why = if (s.createdAt >= prevEnd - 200) "जवाब का text देर से आया" else "बनने में $build ms लगे"
        hooks.log("आवाज़ में चुप्पी $gap ms (\"${s.text.take(30)}\" से पहले): $why")
    }

    /** इंजन फ़ेल होकर phone पर आया हो तो END तक रुककर पता चलता है */
    private fun waitPhone(s: Seg): Boolean {
        val start = System.currentTimeMillis()
        val t = start + 12_000
        // पहला टुकड़ा: जवाब के पहले text से गिनो (इतनी देर आप पहले ही रुक चुके); बाद वाला: पिछला बजने के बाद से
        val first = firstPending
        val from = if (first) s.createdAt else maxOf(start, playUntil)
        var asked = false
        while (s.gen == gen && System.currentTimeMillis() < t) {
            if (s.phone) return true
            if (s.q.isNotEmpty()) return s.phone
            val waited = System.currentTimeMillis() - from
            // नवंबर 2026: टुकड़ा अटका (धीमा नेट / TTS देर) - Live की अपनी आवाज़ बचा जवाब बोले
            // 19 नवंबर 2026: फ़ोन पर बनने वाली आवाज़ (St3) को ज़्यादा समय - वह पूरा टुकड़ा बनाकर ही बजती है
            val late = com.dk.assistant.SplitRescue.stuck(waited, first) &&
                (engine?.id != St3VoiceEngine.ID || com.dk.assistant.SplitRescue.stuck(waited, first, local = true))
            if (!asked && late) {
                asked = true
                if (rescue(s, SplitRescueWhy.STUCK, waited)) return false
            }
            Thread.sleep(10)
        }
        return s.phone
    }

    private enum class SplitRescueWhy { FAILED, STUCK }

    /** Live की अपनी आवाज़ से कहो: [s] से आगे का जवाब तुम बोलो. हाँ = pipeline चुप ([handOff]) */
    private fun rescue(s: Seg, why: SplitRescueWhy, waited: Long): Boolean {
        if (s.gen != gen || released) return false
        val text = com.dk.assistant.SplitRescue.why(why == SplitRescueWhy.FAILED, waited)
        val ok = try { hooks.takeOver(s.at, text) } catch (_: Exception) { false }
        if (!ok) return false
        noteSpoken(s.text)
        handOff()
        return true
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
        tuneVer = VoiceTune.apply(tr, tuneVer)      // v3.80: गति / पिच बदली -> अगले टुकड़े से ही
        val startAt = maxOf(playUntil, now)
        playUntil = startAt + VoiceTune.playMs(pcm.size, VoiceEngine.RATE)
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

    /** नवंबर 2026 (मालिक): स्क्रीन पर - यह टुकड़ा सच में किस इंजन / आवाज़ से बजा */
    private fun showVoice(s: Seg) {
        try {
            val e = VoiceEngines.byId(s.engine) ?: return
            val v = e.voice(ctx)
            val vt = try { e.voices().firstOrNull { it.first == v }?.second ?: v } catch (_: Throwable) { v }
            val chosen = engine
            val why = when {
                chosen != null && chosen.id != e.id -> "${chosen.title} नहीं चला"
                else -> ""
            }
            VoiceNow.played(e.id, e.title, vt.substringBefore(" —").take(40), why)
        } catch (_: Throwable) {}
    }

    private fun noteSpoken(t: String) {
        val all = synchronized(spoken) { if (spoken.isNotEmpty()) spoken.append(' '); spoken.append(t); spoken.toString() }
        hooks.onSpoken(all)
    }

    companion object {
        /** जवाब के बीच दो टुकड़ों का ठहराव इतना तक = अब भी "बोल रहा है" (गूँज को आपकी आवाज़ न समझे) */
        const val GAP_HOLD_MS = 1_200L
        /** फ़ोन वाली आवाज़: पहला टुकड़ा बजाने से पहले दूसरे का ज़्यादा से ज़्यादा इतना इंतज़ार */
        const val PREROLL_MS = 400L
        /** पहला टुकड़ा इतना लंबा = दूसरे को बनने का समय पहले से है, इंतज़ार नहीं */
        const val PREROLL_SKIP_MS = 3_000L
        /** 20 नवंबर 2026: पहला टुकड़ा बजाने से पहले कम से कम इतनी आवाज़ तैयार हो (यह + अगले) */
        const val PREROLL_READY_MS = 1_500L
        /** ...पर इतने से ज़्यादा इंतज़ार नहीं (पहली आवाज़ बहुत देर से न आए) */
        const val PREROLL_WAIT_MS = 1_200L
        /** ऑफ़लाइन आवाज़ बनना/बजना ख़त्म होने के बाद इतनी देर और पीछे के भारी काम रुकें */
        const val BUSY_HOLD_MS = 1_500L
        /** इससे लंबी चुप्पी लॉग में */
        const val GAP_LOG_MS = 250L

        /** इंजन के हिसाब से टुकड़ों का नाप (नवंबर 2026: Kokoro हटाया - अब सब इंजन STANDARD; LOCAL फ़ोन में बनने वाली आवाज़ के लिए रखा) */
        /** नवंबर 2026 (मालिक: "बात खत्म होते ही जवाब शुरू"): Gemini TTS = पहला टुकड़ा छोटा (2-6 शब्द, 150 ms रुका तो तुरंत) */
        fun profileFor(engineId: String): TextChunker.Profile = when (engineId) {
            VoiceEngines.GEMINI_TTS -> TextChunker.FAST_NET
            St3VoiceEngine.ID -> TextChunker.ST3        // 19 नवंबर 2026: फ़ोन पर बनने वाली आवाज़ - शब्द-दर-शब्द नहीं, वाक्य के टुकड़े
            else -> TextChunker.STANDARD
        }

        /** नवंबर 2026: आवाज़ इंजन बदला - जो भी pipeline बोल रही हो, उसी पल चुप (कतार भी) */
        private val alive = java.util.WeakHashMap<SpeechPipeline, Boolean>()
        fun stopAll() { synchronized(alive) { alive.keys.toList() }.forEach { try { it.stop() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } } }

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
