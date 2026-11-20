package com.dk.assistant

import kotlinx.coroutines.delay
import org.json.JSONObject

// GeminiLive का हिस्सा (20 नवंबर 2026, मालिक - हमदर्द मोड): कितनी देर बात हुई, देर रात / खाने का समय - DK एक बार प्यार से बताए.
// हमदर्द मोड बंद = कुछ नहीं. बात चलते समय (यूज़र/DK पिछले 2 मिनट में बोले) ही गिनती; DK चुप हो तभी SYSTEM NOTE ([HamdardClock]).

internal suspend fun GeminiLive.careLoop() {
    while (!stopped) {
        delay(30_000)
        if (stopped) break
        try {
            if (AssistantPersona.active(svc) != AssistantPersona.A.HAMDARD) continue
            val now = System.currentTimeMillis()
            if (now - lastAct > 2 * 60_000L) continue
            HamdardClock.touch(now)
            val t = NowClock.now()
            val key = HamdardClock.dueNow(now, NowClock.hour(t)) ?: continue
            if (!canTalk() || svc.callBusy() || !waitQuiet(60_000) || !canTalk()) continue
            val note = HamdardClock.note(key, HamdardClock.minutes(now), NowClock.spoken(t))
            val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", note)).toString()) == true } catch (_: Exception) { false }
            if (ok) HamdardClock.mark(key)
            lastAct = System.currentTimeMillis()
            log("हमदर्द मोड: $key (${HamdardClock.minutes(now)} मिनट बात) - DK को एक बार बताने को कहा" + if (ok) "" else " (भेज नहीं पाया)")
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Quiet.note(e) }
    }
}
