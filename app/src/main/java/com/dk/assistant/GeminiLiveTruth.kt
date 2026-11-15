package com.dk.assistant

import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * 15 नवंबर 2026 (मालिक: "कोई कॉल नहीं आ रहा, फिर भी बोलता है कॉल आ रहा है ... झूठा दावा ना करे"):
 * जड़ - Live को घंटी की ख़बर (SYSTEM NOTE) जाती थी, पर घंटी बंद होने (कटी/छूटी/उठ गई) की ख़बर कभी नहीं.
 * Live की याद में "कॉल आ रहा है" बना रहता था और बाद में फिर बोल देता था.
 * अब: (1) घंटी बंद होते ही चुपचाप बताओ (जवाब नहीं बनता), (2) फिर भी बोले तो फ़ोन की असली हालत से जाँचो -
 * कोई कॉल नहीं = वह बात वहीं रोको और सच बोलने को कहो.
 */

/** चुपचाप बात (turnComplete=false = Live इस पर जवाब नहीं बनाता) - बोलते/tool के बीच नहीं (1007/1011 से बचाव) */
internal fun GeminiLive.quietNote(t: String) {
    svc.scope.launch {
        if (!waitQuiet(15_000)) { log("Live चुप नहीं हुआ - चुपचाप वाली बात नहीं भेजी: ${t.take(50)}"); return@launch }
        if (!canTalk()) return@launch
        val msg = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", t)))))
            .put("turnComplete", false)).toString()
        val ok = try { ws?.tx(msg) == true } catch (_: Exception) { false }
        log("Live को चुपचाप बताया: ${t.take(70)}" + if (ok) "" else " (भेज नहीं पाया)")
    }
}

/** घंटी बंद हुई (कटी/छूटी = ENDED, उठ गई = ANSWERED) */
internal fun GeminiLive.noteCallGone(who: String, how: TruthCheck.CallEnd) {
    lastCallGoneAt = System.currentTimeMillis()
    quietNote(TruthCheck.callGoneNote(who, how))
}

/** Live की लिखावट (पूरे वाक्य) में झूठा "कॉल आ रहा है" = उसी पल रोको, सच बोलने को कहो. true = रोका */
internal fun GeminiLive.falseCallCheck(soFar: String): Boolean {
    val now = System.currentTimeMillis()
    if (now - falseCallAt < 20_000L) return false        // एक बार काफ़ी - बार-बार टोकना नहीं
    val done = soFar.substring(0, soFar.indexOfLast { it in "।.!?\n" } + 1)
    if (done.isBlank()) return false
    val inCall = try { svc.calls.inCall } catch (_: Exception) { false }
    if (!TruthCheck.falseCall(done, svc.callRinging(), inCall || AppCall.active(), callNoteAt, lastCallGoneAt, now)) return false
    falseCallAt = now
    flushPlayback()
    muteTurn = true; muteAt = now
    log("झूठा कॉल-दावा रोका (फ़ोन पर कोई कॉल नहीं): ${done.take(60)}")
    visionNote(TruthCheck.falseCallNote(done), evidence = false)
    return true
}
