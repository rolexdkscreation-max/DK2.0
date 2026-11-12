package com.dk.assistant

import com.dk.assistant.GeminiLive.Companion.OUT_RATE

// GeminiLive का हिस्सा (नवंबर 2026, मालिक: "Gemini TTS चालू हो तो जवाब देर से, कभी लिखा आता है पर बोलता नहीं").
// बँटवारे में Live की अपनी आवाज़ फेंकी नहीं जाती - इस जवाब के लिए रखी जाती है. Gemini TTS पहली आवाज़ में देर करे
// (SplitRescue.FIRST_BUDGET_MS) या कोई टुकड़ा न बना पाए / अटके -> बचा जवाब उसी पल Live की अपनी आवाज़ में.
// नियम/हिसाब SplitRescue में (JVM test).

/** बँटवारे में Live का आवाज़-टुकड़ा: true = रख लिया (बजाना नहीं); false = Live आवाज़ ही चल रही, आम रास्ते से बजाओ */
internal fun GeminiLive.keepSplitAudio(pcm: ByteArray): Boolean {
    if (splitLive) return false
    if (leakMute) return true                 // v4.1: "phone task request…" जैसी आवाज़ - रखनी भी नहीं
    val len = synchronized(turnLock) { replyText.length }
    synchronized(liveKeep) {
        if (splitLive) return false
        if (liveKeepBytes + pcm.size > SplitRescue.MAX_KEEP_BYTES) return true
        if (liveKeep.isEmpty()) liveKeepFirstAt = System.currentTimeMillis()
        liveKeep.add(pcm); liveKeepLens.add(len); liveKeepBytes += pcm.size
    }
    return true
}

/** नया जवाब: रखी आवाज़ खाली, फिर से Gemini TTS बोलेगा */
internal fun GeminiLive.resetLiveKeep() {
    synchronized(liveKeep) { dropKeepLocked(); splitLive = false; splitLiveWhy = "" }
}

/** टोका / बासी जवाब: रखी आवाज़ फेंको (बजेगी नहीं) */
internal fun GeminiLive.dropLiveKeep() {
    synchronized(liveKeep) { dropKeepLocked() }
}

private fun GeminiLive.dropKeepLocked() {
    liveKeep.clear(); liveKeepLens.clear(); liveKeepBytes = 0; liveKeepFirstAt = 0L; liveRaceFrom = 0L
}

/**
 * Gemini TTS की जगह Live की अपनी आवाज़: बचा जवाब लिखावट के [at] अक्षर से.
 * true = Live की आवाज़ बजने लगी (pipeline चुप करो). false = रखी आवाज़ नहीं (पुराना रास्ता: सिर्फ़ लिखा).
 */
internal fun GeminiLive.rescueWithLive(at: Int, why: String): Boolean {
    if (!connSplit || stopped || dropOld || muteTurn) return false
    var from = 0; var played = 0L; var total = 0L; var n = 0
    synchronized(liveKeep) {
        if (splitLive) return true
        if (liveKeep.isEmpty()) return false
        from = SplitRescue.startIndex(at, liveKeepLens, liveKeep.map { it.size }, OUT_RATE)
        for (i in liveKeep.indices) { total += liveKeep[i].size; if (i < from) played += liveKeep[i].size }
        splitLive = true
        // पहले की गिनती: जितनी आवाज़ TTS बोल चुका, उतनी "बजी" (स्क्रीन का text आवाज़ के साथ-साथ - revealTick)
        turnRecvBytes = total; turnPlayedBytes = played
        // lock के अंदर ही कतार में - बीच में आया नया Live टुकड़ा इनसे आगे न निकले
        for (i in from until liveKeep.size) { val b = liveKeep[i]; if (actHold) holdAct(b) else playOut(b); n++ }
        dropKeepLocked()
    }
    splitRescues++
    splitLiveWhy = why
    if (pipelineLazy.isInitialized()) pipeline.handOff()
    LatencyBoard.count("Gemini TTS देर/चुप: Live की अपनी आवाज़ ने बोला")
    log("आवाज़: $why - बचा जवाब Live की अपनी आवाज़ में (टुकड़ा $from से, ${n} टुकड़े, ${(total - played) * 1000 / (OUT_RATE * 2)} ms) • #$splitRescues")
    return true
}

/** mic loop हर chunk पर: Live की आवाज़ तैयार है और Gemini TTS अब तक नहीं बजा? (पहली आवाज़ की दौड़) */
internal fun GeminiLive.splitRaceCheck(now: Long) {
    if (!connSplit || splitLive || liveKeepFirstAt == 0L) return
    val open = !actHold && !muteTurn && !dropOld && !startHold.active && !leakMute
    if (!open) { liveRaceFrom = 0L; return }                 // रोक (सच-जाँच / आप बोल रहे) - घड़ी खुलने पर शुरू
    if (liveRaceFrom == 0L) liveRaceFrom = now
    val since = maxOf(liveKeepFirstAt, liveRaceFrom)
    val started = pipelineLazy.isInitialized() && pipeline.audioStarted
    // नवंबर 2026 (मालिक: "Piper इस्तेमाल ही नहीं हो पाता, देर करके सीधे Gemini"): 900 ms की दौड़ Gemini TTS (नेट) के लिए है.
    // Piper फ़ोन में बनता है (लिखावट आने + वाक्य बनने में ज़्यादा) - उसे उसके नापे समय जितनी सीमा (PiperTiming)
    if (splitEngine?.id == com.dk.assistant.voice.engine.PiperTtsEngine.ID) {
        val P = com.dk.assistant.voice.engine.PiperTiming
        if (P.firstTooSlow(now, since, started, open, P.typicalFirstMs())) rescueWithLive(0, P.rescueWhy(now - since, false))
        return
    }
    if (SplitRescue.firstTooSlow(now, since, started, open)) rescueWithLive(0, "Gemini TTS की पहली आवाज़ ${now - since} ms में नहीं आई")
}
