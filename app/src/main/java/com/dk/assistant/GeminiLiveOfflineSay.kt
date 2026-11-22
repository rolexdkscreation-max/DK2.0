package com.dk.assistant

/**
 * 20 नवंबर 2026 (मालिक: "कभी Gemini की आवाज़ न आ पाए / सिर्फ़ टेक्स्ट आ गए / वो बोला नहीं, तो फ़ोन में लगा
 * ऑफ़लाइन वाला TTS बोले"): DK लाइव की सीधी आवाज़ (बँटवारा नहीं) में जवाब पूरा हुआ, लिखावट आई पर आवाज़ का एक भी
 * टुकड़ा नहीं = वही लिखा जवाब DK की ऑफ़लाइन आवाज़ (St3) से; वह डाउनलोड न हो तो Gemini TTS से ([com.dk.assistant.voice.engine.SpeechPipeline]
 * का अपना क्रम: चुना इंजन -> Gemini TTS -> ऑफ़लाइन). बँटवारे वाला रास्ता पहले से अपना सहारा रखता है - उसे यह नहीं छूता.
 */
object LiveTextOnly {
    /** कोई बैकअप आवाज़ तैयार नहीं - स्क्रीन पर यही (10 मिनट में एक बार) */
    const val NO_VOICE_HINT = "आवाज़ नहीं आई - बैकअप के लिए Settings › आवाज़ › \"आवाज़ें खोलें\" में ऑफ़लाइन आवाज़ डाउनलोड करें"
    /** इससे छोटी लिखावट (जैसे "।") नहीं बोलते */
    const val MIN_CHARS = 2

    /**
     * pure (JVM test): सीधी आवाज़ वाले जवाब के अंत में - क्या लिखा जवाब ऑफ़लाइन आवाज़ से बोलें?
     * split = बँटवारा चालू, dropped = टोका गया / दूसरे की बात, muted = फ़ोन चुपचाप काम कर रहा था (leakMute),
     * waiting = आवाज़ अभी किसी फ़ैसले के लिए रुकी है (फ़ोन का काम / जवाब की शुरुआत रुकी), recvBytes = इस जवाब की बजी/आई आवाज़.
     */
    fun need(split: Boolean, dropped: Boolean, muted: Boolean, waiting: Boolean, recvBytes: Long, text: String): Boolean =
        !split && !dropped && !muted && !waiting && recvBytes <= 0L && text.trim().length >= MIN_CHARS

    // ---------------------------------------------------------------- 22 नवंबर 2026: आधी आवाज़
    // मालिक: "कभी-कभी टेक्स्ट आ जाता है लेकिन वॉइस नहीं बोलता". जड़ - ऊपर का नियम सिर्फ़ तब था जब Live ने आवाज़ का एक भी
    // टुकड़ा न भेजा हो. पर अक्सर Live पहला वाक्य बोलकर आवाज़ भेजना बंद कर देता है (लिखावट पूरी आती है), या आवाज़ आई पर
    // फ़ोन पर बजी नहीं (AudioTrack लिखने में गलती). तब बाकी जवाब सिर्फ़ लिखा रहता था. अब: आवाज़ बजकर रुकने के बाद
    // देखो कितनी बजी - बचा हिस्सा (वाक्य/शब्द की सीमा से) ऑफ़लाइन आवाज़ से.

    /** Live की सबसे तेज़ बोली: इतने अक्षर / सेकंड (ऊँचा अंदाज़ = दोहराव नहीं, ज़्यादा से ज़्यादा 1-2 शब्द छूटें) */
    const val FAST_CPS = 22.0
    /** इससे कम बचा हो तो नहीं बोलते (आख़िरी शब्द / नाप की गलती) */
    const val MIN_REST = 24
    /** आवाज़ थमने का इंतज़ार (इससे ज़्यादा = बजती आवाज़ के ऊपर नहीं बोलेंगे) */
    const val DRAIN_MAX_MS = 45_000L
    /** Live की आवाज़: 24 kHz, 16-bit mono */
    const val OUT_BYTES_PER_SEC = 24_000 * 2

    /** कुछ आवाज़ आई - बजने के बाद जाँचना है? (सीधी आवाज़, टोका/चुप/रुका नहीं) */
    fun check(split: Boolean, dropped: Boolean, muted: Boolean, waiting: Boolean, recvBytes: Long, text: String): Boolean =
        !split && !dropped && !muted && !waiting && recvBytes > 0L && text.trim().length >= MIN_REST

    /** [playedBytes] बजी आवाज़ के बाद जवाब का जो हिस्सा नहीं बोला गया (नहीं = null) - pure, JVM test */
    fun rest(text: String, playedBytes: Long): String? {
        val t = text.trim()
        val covered = (playedBytes.coerceAtLeast(0L).toDouble() / OUT_BYTES_PER_SEC * FAST_CPS).toInt()
        if (t.length - covered < MIN_REST) return null
        if (covered <= 0) return t
        // बोला हुआ हिस्सा जहाँ ख़त्म हुआ उसके बाद वाले वाक्य से; वाक्य दूर हो तो अगले शब्द से
        val ends = charArrayOf('।', '.', '?', '!', '\n')
        var at = -1
        for (i in covered until minOf(t.length, covered + 60)) if (t[i] in ends) { at = i + 1; break }
        if (at < 0) { at = t.indexOf(' ', covered).let { if (it < 0) t.length else it + 1 } }
        val r = t.substring(minOf(at, t.length)).trim()
        return r.takeIf { it.length >= MIN_REST }
    }
}

/** turnComplete पर (finishTurn से पहले) - [LiveTextOnly.need] हाँ कहे तो लिखा जवाब बोलो; आधी आवाज़ = बजने के बाद बचा हिस्सा */
internal fun GeminiLive.offlineTextOnly(dropped: Boolean, muted: Boolean) {
    val text = synchronized(turnLock) { replyText.toString() }.trim()
    val waiting = fastJob?.isActive == true || startHold.active
    if (LiveTextOnly.need(connSplit, dropped, muted, waiting, turnRecvBytes, text)) { sayOffline(text, "Live ने जवाब लिखा पर आवाज़ नहीं भेजी"); return }
    if (!LiveTextOnly.check(connSplit, dropped, muted, waiting, turnRecvBytes, text)) return
    // 22 नवंबर 2026: आवाज़ आई - पर पूरी बजी? बजना थमने तक रुको (टोका / नया जवाब / आप बोले = कुछ नहीं)
    val g = gen; val seq = replySeq
    svc.scope.launch {
        kotlinx.coroutines.delay(300)
        kotlinx.coroutines.withTimeoutOrNull(LiveTextOnly.DRAIN_MAX_MS) { while (!stopped && gen == g && replySeq == seq && isPlaying()) kotlinx.coroutines.delay(120) }
        if (stopped || gen != g || replySeq != seq || isPlaying() || turnDet.inSpeech || dropOld || muteTurn) return@launch
        val played = turnPlayedBytes
        val rest = LiveTextOnly.rest(text, played) ?: return@launch
        log("Live की आवाज़ बीच में रुकी (${played * 1000 / LiveTextOnly.OUT_BYTES_PER_SEC} ms बजी, जवाब ${text.length} अक्षर) - बचा हिस्सा (${rest.length} अक्षर)")
        sayOffline(rest, "Live की आवाज़ बीच में रुकी")
    }
}

/** लिखा हुआ ऑफ़लाइन आवाज़ (St3) से; वह न हो तो Gemini TTS. दोनों नहीं = साफ़ बताओ (एक बार) */
internal fun GeminiLive.sayOffline(text: String, why: String) {
    val engines = com.dk.assistant.voice.engine.VoiceEngines
    val e = engines.byId(engines.ST3_OFFLINE)?.takeIf { it.notReady(svc) == null }
        ?: engines.fallback()?.takeIf { it.notReady(svc) == null && !engines.isFailed(it.id) }
    if (e == null) {
        log("$why - ऑफ़लाइन आवाज़ डाउनलोड नहीं, Gemini TTS भी तैयार नहीं (सिर्फ़ लिखा)")
        val now = System.currentTimeMillis()
        if (now - noVoiceHintAt > 10 * 60_000L) { noVoiceHintAt = now; DKState.hint(LiveTextOnly.NO_VOICE_HINT) }
        return
    }
    log("$why (${text.length} अक्षर) - अब ${e.title} बोलेगा")
    try { com.dk.assistant.voice.engine.VoiceNow.played(e.id, e.title, e.voice(svc), why) } catch (ex: Exception) { Quiet.note(ex) }
    pipeline.begin(e)
    pipeline.feed(text)
    pipeline.endTurn()
}
