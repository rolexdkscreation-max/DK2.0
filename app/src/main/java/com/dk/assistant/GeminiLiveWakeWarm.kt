package com.dk.assistant

import com.dk.assistant.wake.WakeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 16 नवंबर 2026 (मालिक: "हे डीके बोलने पर तुरंत एक्टिव नहीं होता"): GeminiLive का हिस्सा - वेक वर्ड से पहले ही Live तैयार.
//
// जड़: वेक वर्ड पूरा पहचानने के *बाद* ही Gemini Live का नया WebSocket खुलता था (DNS + TLS + setup = आम तौर पर 1-3 सेकंड).
// गर्म (standby) connection सिर्फ़ पिछली बातचीत के बाद 5 मिनट तक रहता था - बाकी हर बार ठंडा जुड़ना.
// अब: वेक वर्ड का score सीमा के 40% पार होते ही (आप "हे डीके" बोल ही रहे होते हैं) पीछे से वही (मॉडल × key) दौड़,
// माइक नहीं, कोई आवाज़ नहीं - सिर्फ़ connection + setup. शब्द पूरा होते ही Live उसी को लेता है (आम तौर पर 0 ms).
// गलत अंदाज़ा (आपने हे डीके नहीं बोला) = connection 5 मिनट standby में रहकर अपने-आप बंद. 45 सेकंड में एक से ज़्यादा नहीं.
// वही Live वाली key - कोई नई key नहीं.

@Volatile private var wakeWarmAt = 0L
@Volatile private var wakeWarmRace: GeminiLive.Race? = null

/** वेक वर्ड की आहट: पीछे से Live connection तैयार करो. true = तैयारी शुरू हुई */
internal fun GeminiLive.wakeWarm(why: String): Boolean {
    val now = System.currentTimeMillis()
    if (DKState.live) return false
    if (!WakeRules.warmGapOk(now, wakeWarmAt, ready = warmReady(), busy = raceBusy())) return false
    wakeWarmAt = now
    svc.scope.launch(Dispatchers.IO) {
        try {
            val livePin = BrainPin.resolve(svc, BrainPin.LIVE)
            val own = Prefs.liveGeminiKeys(svc)
            val keys = livePin?.let { listOf(it.key) } ?: curKeys.ifEmpty { own + BrainPin.spareGemini(svc, own) }
            if (keys.isEmpty()) return@launch
            val models = livePin?.let { listOf(it.model.removePrefix("models/")) }
                ?: curModels.ifEmpty { LiveModelPick.order(LiveModelPick.get(svc), discoverModels(keys[0])) }
            if (models.isEmpty() || DKState.live || warmReady() || raceBusy()) return@launch
            spareWanted = false
            planSplit(models[0])          // कौन बोलेगा (Live की आवाज़ / बँटवारा) - वही जो run() तय करेगा, ताकि तैयार connection काम आए
            log("$why: वेक वर्ड की आहट - पीछे से Live का connection पहले से तैयार कर रहा हूँ (माइक/आवाज़ नहीं जाती)")
            wakeWarmRace = startRace(keys, models, live = false, resumeId = null)
        } catch (e: Throwable) { log("$why: पहले से connection नहीं बना: ${e.message}") }
    }
    return true
}

/** run() की शुरुआत में: वेक वर्ड वाली तैयारी अभी चल रही हो तो थोड़ा रुको (नई दौड़ उसे काटकर शून्य से शुरू न करे) */
internal suspend fun GeminiLive.awaitWakeWarm(maxMs: Long = 2_500) {
    val r = wakeWarmRace ?: return
    if (r.over || r.winner.isCompleted) { wakeWarmRace = null; return }
    val t0 = System.currentTimeMillis()
    withTimeoutOrNull(maxMs) { while (!r.over && !r.winner.isCompleted) delay(20) }
    wakeWarmRace = null
    log("वेक वर्ड वाली पहले की तैयारी: " + (if (warmReady()) "connection तैयार मिला" else "अभी नहीं जुड़ा - सामान्य तरीके से") + " (${System.currentTimeMillis() - t0} ms रुका)")
}
