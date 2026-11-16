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

    /**
     * 15 नवंबर 2026 (मालिक: "DK का अपना दिमाग हमेशा सीखता रहे, जितना हो सके; ऑफलाइन में भी अच्छे से काम करे").
     * बिना नेट: पहले रोज़ का सीखा (मिलता-जुलता भी), फिर आम बोलचाल, खोज वाली बात = साफ़ "नेट चाहिए" (कभी अंदाज़ा नहीं).
     */
    fun offline(c: Context, goal: String): String {
        val q = goal.removePrefix("USER SAYS:").trim()
        if (q.isBlank()) return OfflineTalk.DONT_KNOW
        return try { OfflineTalk.reply(c, q, addressed = true) } catch (_: Exception) { null } ?: OfflineTalk.DONT_KNOW
    }

    /** API चलती है, फिर भी वही पक्का (न बदलने वाला, ताज़ा) सवाल पहले पूछा था = अपना जवाब - बातचीत की free request बचे */
    fun saved(c: Context, goal: String): String? =
        try { LocalBrain.recall(c, goal.removePrefix("USER SAYS:").trim(), exact = true) } catch (_: Exception) { null }

    /**
     * हर अच्छे AI जवाब से सीखो (चैट स्क्रीन, होम चैट - पहले सिर्फ़ Accessibility वाली चैट और DK लाइव सीखते थे).
     * खोज वाला जवाब तभी जब सवाल न बदलने वाला हो ([DkMind.teachable] खुद जाँचता है), चिप के निशान हटाकर.
     * फ़ोन के काम / JSON / खाली = नहीं.
     */
    fun learn(c: Context, goal: String, answer: String, searched: Boolean) {
        val q = goal.removePrefix("USER SAYS:").trim(); val a = WebCite.strip(answer).trim()
        if (!worth(a)) return
        if (searched && DkMind.changing(LocalBrain.norm(q))) return
        try { LocalBrain.learn(c, q, a) } catch (e: Exception) { Quiet.note(e) }
    }

    /** सीखने लायक जवाब? (फ़ोन के काम वाला / JSON / "…" नहीं). Pure */
    fun worth(a: String): Boolean = a.length >= 4 && !a.startsWith("{") && a != "…" &&
        !a.contains("[SCREEN]") && !a.contains("need_screen") && !a.contains("Accessibility में DK चालू")
}
