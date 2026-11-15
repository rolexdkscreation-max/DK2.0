package com.dk.assistant

import android.content.Context

/**
 * 15 नवंबर 2026 (मालिक: "चैट करने पर भी कोई प्रॉब्लम नहीं होना चाहिए"): AI से जवाब न आए तो पहले DK का अपना दिमाग -
 * हर चैट रास्ते (DK लाइव / Accessibility वाली चैट, चैट स्क्रीन, होम की चैट) में एक जैसा. पहले यह सिर्फ़ [AgentRunner] में था,
 * चैट स्क्रीन / होम सीधे गलती दिखाते थे.
 * रोज़ का सीखा जवाब ([LocalBrain], इसी फ़ोन में) > आम बोलचाल ([OfflineTalk.casual]). खोज / ताज़ा जानकारी वाली बात = null
 * (अंदाज़ा = गलत जवाब - तब सच्ची गलती ही दिखे).
 */
object OwnMind {
    fun answer(c: Context, goal: String): String? {
        val q = goal.removePrefix("USER SAYS:").trim()
        if (q.isBlank() || OfflineTalk.needsNet(q)) return null
        try { LocalBrain.recall(c, q, exact = false) } catch (_: Exception) { null }?.let { DKLog.d("DK का अपना दिमाग: सीखा जवाब"); return it }
        val x = OfflineTalk.Ctx(Prefs.userName(c), Prefs.assistantName(c), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), (System.nanoTime() / 1000).toInt())
        return try { OfflineTalk.casual(q, x) } catch (_: Exception) { null }?.also { DKLog.d("DK का अपना दिमाग: आम बात") }
    }
}
