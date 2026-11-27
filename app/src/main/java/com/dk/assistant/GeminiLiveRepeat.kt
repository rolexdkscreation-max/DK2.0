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
    val dup = repeatGuard.dupReply(now, utt.lastRealAt())
    if (!dup && !repeatGuard.isRepeat(soFar, user, now)) return false
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
    if (!LiveRepeat.isEcho(heard, lastDkLine, now - playEnd)) return false
    synchronized(turnLock) { userText.clear() }
    muteTurn = true; muteAt = now                       // इस "बात" का जवाब आए तो बजे नहीं (turnComplete पर हटता)
    replyWatch.onServerAnswered()                       // गूँज के लिए "नहीं सुना" बचाव भी नहीं
    log("अपनी ही आवाज़ की गूँज सुनी (${now - playEnd} ms बाद) - उस पर जवाब नहीं: \"${heard.take(50)}\"")
    return true
}
