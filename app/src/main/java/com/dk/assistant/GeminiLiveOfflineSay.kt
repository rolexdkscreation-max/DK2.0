package com.dk.assistant

/**
 * 20 नवंबर 2026 (मालिक: "कभी Gemini की आवाज़ न आ पाए / सिर्फ़ टेक्स्ट आ गए / वो बोला नहीं, तो फ़ोन में लगा
 * ऑफ़लाइन वाला TTS बोले"): DK लाइव की सीधी आवाज़ (बँटवारा नहीं) में जवाब पूरा हुआ, लिखावट आई पर आवाज़ का एक भी
 * टुकड़ा नहीं = वही लिखा जवाब DK की ऑफ़लाइन आवाज़ (St3) से; वह डाउनलोड न हो तो Gemini TTS से ([com.dk.assistant.voice.engine.SpeechPipeline]
 * का अपना क्रम: चुना इंजन -> Gemini TTS -> ऑफ़लाइन). बँटवारे वाला रास्ता पहले से अपना सहारा रखता है - उसे यह नहीं छूता.
 */
object LiveTextOnly {
    /** इससे छोटी लिखावट (जैसे "।") नहीं बोलते */
    const val MIN_CHARS = 2

    /**
     * pure (JVM test): सीधी आवाज़ वाले जवाब के अंत में - क्या लिखा जवाब ऑफ़लाइन आवाज़ से बोलें?
     * split = बँटवारा चालू, dropped = टोका गया / दूसरे की बात, muted = फ़ोन चुपचाप काम कर रहा था (leakMute),
     * waiting = आवाज़ अभी किसी फ़ैसले के लिए रुकी है (फ़ोन का काम / जवाब की शुरुआत रुकी), recvBytes = इस जवाब की बजी/आई आवाज़.
     */
    fun need(split: Boolean, dropped: Boolean, muted: Boolean, waiting: Boolean, recvBytes: Long, text: String): Boolean =
        !split && !dropped && !muted && !waiting && recvBytes <= 0L && text.trim().length >= MIN_CHARS
}

/** turnComplete पर (finishTurn से पहले) - [LiveTextOnly.need] हाँ कहे तो लिखा जवाब बोलो */
internal fun GeminiLive.offlineTextOnly(dropped: Boolean, muted: Boolean) {
    val text = synchronized(turnLock) { replyText.toString() }.trim()
    val waiting = fastJob?.isActive == true || startHold.active
    if (!LiveTextOnly.need(connSplit, dropped, muted, waiting, turnRecvBytes, text)) return
    val engines = com.dk.assistant.voice.engine.VoiceEngines
    val e = engines.byId(engines.ST3_OFFLINE)?.takeIf { it.notReady(svc) == null }
        ?: engines.fallback()?.takeIf { it.notReady(svc) == null }
    if (e == null) { log("Live ने जवाब लिखा पर आवाज़ नहीं भेजी - ऑफ़लाइन आवाज़ डाउनलोड नहीं, Gemini TTS भी तैयार नहीं (सिर्फ़ लिखा)"); return }
    log("Live ने जवाब लिखा पर आवाज़ नहीं भेजी (${text.length} अक्षर) - अब ${e.title} बोलेगा")
    try { com.dk.assistant.voice.engine.VoiceNow.played(e.id, e.title, e.voice(svc), "Live की आवाज़ नहीं आई") } catch (ex: Exception) { Quiet.note(ex) }
    pipeline.begin(e)
    pipeline.feed(text)
    pipeline.endTurn()
}
