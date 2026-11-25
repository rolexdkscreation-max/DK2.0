package com.dk.assistant.agent.social

/**
 * 25 नवंबर 2026 (मालिक: "10 फोटो भेजूँ तो जहाँ बोलूँ वहाँ सब डाल दे"): कई share की गई फोटो/वीडियो का बँटवारा (pure, JVM test).
 * Instagram / Facebook पोस्ट = एक ही पोस्ट में (Instagram 10 तक). स्टोरी / WhatsApp स्टेटस / रील / Shorts = एक-एक करके -
 * पहले सवाल में सबकी बात, एक असली "हाँ" = सब. कोई एक रुके/फ़ेल हो = आगे वाले नहीं (सच बताओ कितने हुए).
 */
object SocialBatch {
    /** onePost = एक पोस्ट में ये सब; queue = एक-एक करके ये; dropped = जो ऐप की हद से बाहर रहीं */
    data class Plan(val onePost: List<SharedItem>, val queue: List<SharedItem>, val dropped: Int)

    const val MAX_QUEUE = 20

    fun split(r: SocialRequest, batch: List<SharedItem>): Plan {
        if (batch.size <= 1) return Plan(emptyList(), emptyList(), 0)
        if (SocialPlanner.canOnePost(r)) {
            val max = SocialPlanner.onePostMax(r.platform)
            return Plan(batch.take(max), emptyList(), (batch.size - max).coerceAtLeast(0))
        }
        return Plan(emptyList(), batch.take(MAX_QUEUE), (batch.size - MAX_QUEUE).coerceAtLeast(0))
    }

    fun ok(s: TaskStatus) = s == TaskStatus.COMPLETED || s == TaskStatus.SUBMITTED_UNVERIFIED

    /** आख़िरी सच्चा हिसाब (Hindi) */
    fun summary(app: String, done: Int, total: Int, last: TaskStatus?): String = when {
        done >= total -> "$app पर सभी $total डाल दिए (हर एक पर ऐप ने भेजना शुरू किया; अपलोड में थोड़ा समय लग सकता है)।"
        done == 0 -> "$app पर कुछ नहीं डाला" + why(last)
        else -> "$app पर $total में से $done डाल दिए, बाक़ी ${total - done} नहीं" + why(last)
    }

    private fun why(s: TaskStatus?) = when (s) {
        TaskStatus.CANCELLED -> " - रोक दिया गया।"
        TaskStatus.FAILED -> " - बीच में अटक गया, इसलिए आगे वाले नहीं डाले।"
        TaskStatus.DRAFT_READY -> " - सिर्फ़ तैयार किया (draft), पोस्ट नहीं।"
        else -> "।"
    }
}
