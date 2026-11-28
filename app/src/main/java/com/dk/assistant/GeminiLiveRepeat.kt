package com.dk.assistant

/**
 * 27 नवंबर 2026 (मालिक: "एक ही सवाल का जवाब 3-4 बार, मेरी बात सुनने की जगह पहले वाली बात दोहराता है").
 * हिसाब [LiveRepeat] में; यहाँ सिर्फ़ Live में जोड़ना.
 */

/** अभी बन रहा जवाब दोहराव है? हाँ = बची आवाज़ रोको, बाकी हिस्से फेंको (टोकने जैसा), आप जो बोलें उसे सुनो. true = रोका */
internal fun GeminiLive.repeatCheck(): Boolean {
    if (dropOld || muteTurn) return false
    val now = System.currentTimeMillis()
    val (soFar, heard) = synchronized(turnLock) { replyText.toString().take(REPEAT_PEEK) to userText.toString() }
    val user = heard.trim().ifBlank { caption?.text().orEmpty().trim() }.ifBlank { turnUser }
    val dup = repeatGuard.dupReply(now, maxOf(utt.lastRealAt(), speechStartAt))   // 28 नवंबर: फ़ोन VAD ने भी सुना = नई बात
    // 28 नवंबर 2026 (मालिक: "रोका तो जवाब ही नहीं आया"): ताज़ा जवाब माँगने के बाद वाला जवाब नहीं रोकते (चक्कर नहीं)
    if (!dup && (now - freshAt < LiveRepeat.FRESH_GAP_MS || !repeatGuard.isRepeat(soFar, user, now))) return false
    // वही पुराना जवाब = रोको, और turn खत्म होते ही आपकी इसी बात का नया जवाब माँगो (चुप नहीं)
    if (!dup && user.isNotBlank()) freshAsk = user
    log(
        if (dup) "दोहराव रोका: दोबारा भेजी बात का जवाब पहले ही दे चुका - दूसरा जवाब नहीं बजाया"
        else "दोहराव रोका: यह जवाब पिछले जवाब जैसा ही शुरू हुआ (आपने दोबारा नहीं माँगा): \"${soFar.take(50)}\""
    )
    localBargeAt = now
    dropOld = true                     // बचे टुकड़े turnComplete तक फेंको (वहीं बारी बंद)
    stopForUser("दोहराव")
    return true
}

private const val REPEAT_PEEK = 400

/** Gemini ने जो "आपकी बात" सुनी वह DK की अपनी ही अभी-खत्म हुई आवाज़ की गूँज है? हाँ = उस पर जवाब नहीं. true = गूँज */
internal fun GeminiLive.echoHeard(t: String): Boolean {
    val now = System.currentTimeMillis()
    if (generating || isPlaying()) return false        // DK बोल रहा है - वहाँ पुरानी बीच-में-टोकना जाँच ([BargeGate])
    val heard = synchronized(turnLock) { (userText.toString() + t).trim() }
    val after = if (speechStartAt > 0L) speechStartAt - playEnd else Long.MIN_VALUE
    if (!LiveRepeat.isEcho(heard, lastDkLine, now - playEnd, after)) return false
    synchronized(turnLock) { userText.clear() }
    muteTurn = true; muteAt = now                       // इस "बात" का जवाब आए तो बजे नहीं (turnComplete पर हटता)
    replyWatch.onServerAnswered()                       // गूँज के लिए "नहीं सुना" बचाव भी नहीं
    log("अपनी ही आवाज़ की गूँज सुनी (${now - playEnd} ms बाद) - उस पर जवाब नहीं: \"${heard.take(50)}\"")
    return true
}

/**
 * 28 नवंबर 2026 (मालिक का लॉग: "क्या कर रहे हो" पर हर बार वही "अच्छा, सुनो..." और रोकने पर कोई जवाब नहीं):
 * दोहराव रुका = आपकी इसी बात का नया, सीधा जवाब माँगो (एक बार, [LiveRepeat.FRESH_GAP_MS] में).
 */
internal fun GeminiLive.askFresh(user: String) {
    val now = System.currentTimeMillis()
    if (!ready || ws == null || muteTurn || now - freshAt < LiveRepeat.FRESH_GAP_MS) return
    val ok = try { ws?.tx(org.json.JSONObject().put("realtimeInput", org.json.JSONObject().put("text", LiveRepeat.freshNote(user))).toString()) == true } catch (_: Exception) { false }
    if (!ok) return
    freshAt = now
    eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
    timeline.begin(now, now, "दोहराव के बाद नया जवाब"); waitingLogged = false
    log("दोहराव रोका - आपकी इसी बात का नया जवाब माँगा: \"${user.take(50)}\"")
    if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
}
