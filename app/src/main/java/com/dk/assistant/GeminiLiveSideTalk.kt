package com.dk.assistant

// GeminiLive का हिस्सा (नवंबर 2026, मालिक): "मैं किसी और से बात करूँ तो DK सुनेगा, लेकिन जवाब नहीं देगा."
// आवाज़ Gemini तक जाती रहती है (बात का संदर्भ याद रहे) - बस उस बात का जवाब न बजे, न कोई काम हो.

/** बोलना खत्म: आवाज़ किसकी (बॉस / कोई और) - अलग thread पर, mic नहीं रुकता */
internal fun GeminiLive.whoCheckAsync(pcm: ByteArray, now: Long, seq: Int) {
    val ms = pcm.size * 1000 / (GeminiLive.IN_RATE * 2)
    if (ms < SideTalk.MIN_MS) { SideTalk.global.voice(SideTalk.Who.UNKNOWN, now, seq); return }
    val max = GeminiLive.IN_RATE * 2 * 3
    val clip = if (pcm.size > max) pcm.copyOfRange(pcm.size - max, pcm.size) else pcm
    val th = com.dk.assistant.voice.VoicePrint.threshold(false)
    try {
        com.dk.assistant.voice.OwnerVoice.exec.execute {
            val sc = try { com.dk.assistant.voice.OwnerVoice.score(svc, clip) } catch (_: Throwable) { null }
            val w = SideTalk.classify(sc, th, minOf(ms, 3000))
            SideTalk.global.voice(w, now, seq)
            if (w == SideTalk.Who.OTHER) log("आवाज़ बॉस की नहीं लगी (मिलान ${sc?.let { String.format(java.util.Locale.US, "%.2f", it) }}) - बड़े काम नहीं होंगे")
        }
    } catch (_: Exception) {}
}

/**
 * जवाब का पहला हिस्सा आया: यह बात DK से थी या किसी और से? किसी और से = यह जवाब न बजे (muteTurn).
 * @return true = अभी चुप कराया
 */
internal fun GeminiLive.sideTalkCheck(): Boolean {
    if (muteTurn || replyStarted || dropOld) return false
    val c = try { svc } catch (_: Throwable) { return false }
    if (!SideTalk.on(c) && !SideTalk.global.listening(System.currentTimeMillis())) return false
    val heard = (synchronized(turnLock) { userText.toString() } + " " + turnUser + " " + caption?.text().orEmpty()).trim()
    val now = System.currentTimeMillis()
    val why = SideTalk.global.muteReason(now, heard, Prefs.wakeWords(c)) ?: return false
    muteTurn = true; muteAt = now
    log("बगल की बातचीत ($why) - सुना, जवाब नहीं: \"${heard.take(40)}\"")
    return true
}

/** इस बात की आवाज़ किसी और की (ताज़ा पहचान) - बड़े काम रोकने के लिए */
internal fun GeminiLive.sideWho(): SideTalk.Who = if (whoOn) SideTalk.global.current(System.currentTimeMillis()) else SideTalk.Who.UNKNOWN
