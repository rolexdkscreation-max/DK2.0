package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक: "DK का जवाब पहले हिंदी में दिखता है फिर तुरंत इंग्लिश में अनुवाद हो जाता है... अनुवाद करने की कोई ज़रूरत नहीं,
 * टेक्स्ट हिंदी में ही दिखना चाहिए, ट्रांसलेट नहीं होना चाहिए").
 * ऐप की भाषा English / दूसरी हो तब भी बातचीत (आपकी बोली बात + DK का जवाब) कभी अनुवाद नहीं - सिर्फ़ ऐप के बटन / लेबल.
 */
object ConvoText {
    private val LABEL = Regex("^\\s*[^\\s:：]{1,12}(?:\\s[^\\s:：]{1,8})?\\s*[:：]\\s+")

    fun norm(t: String): String = t.replace(LABEL, "").replace('…', ' ').replace(Regex("\\s+"), " ").trim()

    /** pure: [text] बातचीत का हिस्सा है ([pool] = हाल की बातें)? छोटा text पूरा मिलना चाहिए (बटन "सेटिंग" जैसे शब्द नहीं रुकें) */
    fun isConvo(text: String, pool: List<String>): Boolean {
        val n = norm(text)
        if (n.length < 2) return false
        for (raw in pool) {
            val p = norm(raw)
            if (p.length < 2) continue
            if (p == n) return true
            if (n.length >= 12 && p.contains(n)) return true
            if (p.length >= 12 && n.contains(p)) return true
        }
        return false
    }

    /** अभी की बातचीत (DK का जवाब, आपकी बात, हाल के संदेश) */
    fun pool(): List<String> {
        val out = ArrayList<String>(16)
        out += DKState.lastReply; out += DKState.liveReply; out += DKState.lastUser
        val m = DKState.messages
        for (i in maxOf(0, m.size - 12) until m.size) m.getOrNull(i)?.let { out += it.text }
        return out
    }

    fun isConvoNow(text: String): Boolean = try { isConvo(text, pool()) } catch (e: Exception) { Quiet.note(e); false }
}
