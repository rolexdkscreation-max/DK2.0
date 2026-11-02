package com.dk.assistant

import android.util.Base64
import com.dk.assistant.GeminiLive.Companion.CHUNK_MS
import com.dk.assistant.GeminiLive.Companion.IN_RATE

// GeminiLive का हिस्सा (नवंबर 2026, DKI-406DD9 / DKI-70D747 / DKI-D24C3A): टूटने / अटकने के बाद यूज़र की बात न खोए.
// नियम LiveRecover.kt में (unit test); यहाँ सिर्फ़ जोड़ना.

internal fun liveAudioMsg(pcm: ByteArray): String =
    "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$IN_RATE\",\"data\":\"" +
        Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"

/**
 * नए connection पर (setupComplete / गर्म connection) - अटके या टूटे session में गई आपकी बात की आवाज़ दोबारा.
 * text नहीं (बीच बातचीत का text = 1011). sendLock के अंदर, flushPending के बाद बुलाओ.
 */
internal fun GeminiLive.sendReplay() {
    val a = replayAudio ?: return
    replayAudio = null
    val w = ws ?: return
    val now = System.currentTimeMillis()
    if (a.isEmpty() || now - replayAt > 20_000L) return
    // इस बीच आप नई बात बोल चुके (वह अभी भेजी गई) = पुरानी दोबारा नहीं, नई का जवाब आएगा
    if (utt.lastRealAt() > replayAt) { log("Live: आप नई बात बोल चुके - पुरानी आवाज़ दोबारा नहीं भेजी"); return }
    var n = 0
    for (c in a) if (w.tx(liveAudioMsg(c))) n++
    val quiet = ByteArray(a.first().size)
    repeat(LiveRecover.REPLAY_TAIL_MS / CHUNK_MS) { w.tx(liveAudioMsg(quiet)) }
    try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
    replayedN++
    // पुरानी बात की समय-रेखा "कुछ नहीं आया" में न गिने - वही बात अब यहाँ से
    timeline.reset()
    eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
    beginTimeline(now, now, "अटकी/टूटी बात की आवाज़ दोबारा")
    replyWatch.onResent()
    log("Live: आपकी बात की आवाज़ (${n * CHUNK_MS / 100 / 10.0}s) नए connection पर दोबारा भेजी - text नहीं (#$replayedN)")
    if (!isPlaying() && DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
}

/** गड़बड़ी के बाद server फिर बोला = संभल गया (Inspector इसी निशान से पिछली "टूटा/अटका" लाइन को गलती नहीं गिनता) */
internal fun GeminiLive.onRecovered() {
    val why = recoverWhy ?: return
    recoverWhy = null
    val ms = System.currentTimeMillis() - recoverAt
    log("${LiveRecover.OK_MARK} - $why के बाद ${ms / 100 / 10.0}s में server फिर जवाब दे रहा है")
}
