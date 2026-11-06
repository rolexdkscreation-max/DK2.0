package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): "DK ऑन रहे और मैं रील देखूँ तो ऊपर छोटी पट्टी में दिखे - कितनी रील स्क्रॉल हुईं / दिखीं (एक्यूरेट),
 * वही रील दोबारा देखी तो वह भी गिने. YouTube शॉर्ट्स, फेसबुक वीडियो/रील, इंस्टाग्राम रील - सब. नोटिफ़िकेशन में नहीं.
 * सेटिंग में हर ऐप की लिमिट - पूरी होते ही फ़ोन लॉक या वह ऐप बंद, और जब तक DK सेटिंग से खोलें नहीं, वह ऐप न खुले."
 *
 * यह हिस्सा pure (JVM test): कौन सा ऐप, रील वाली स्क्रीन है या नहीं, अभी कौन सी रील (पहचान), कब +1, लिमिट.
 * Android वाला हिस्सा: [ReelWatch].
 *
 * पहचान कैसे (सच): ये ऐप अपनी रील की कोई ID बाहर नहीं देते. DK स्क्रीन पर लिखा पढ़ता है (accessibility) -
 * रील वाली स्क्रीन = दाईं तरफ़ ऊपर-नीचे लाइक / कमेंट / शेयर के बटन (या ऐप के रील वाले view id);
 * कौन सी रील = नीचे का लिखा (बनाने वाले का नाम, caption, गाना). यह बदला और 0.7 सेकंड टिका = नई रील (+1).
 * पीछे स्क्रॉल करके पुरानी रील फिर आई = वह भी बदलाव = +1 (मालिक की माँग: दोबारा देखी तो भी गिनो).
 */
object ReelRules {
    enum class App(val key: String, val hi: String, val pkgs: List<String>) {
        INSTAGRAM("ig", "इंस्टाग्राम", listOf("com.instagram.android", "com.instagram.lite", "com.instagram.barcelona.reels")),
        YOUTUBE("yt", "YouTube शॉर्ट्स", listOf("com.google.android.youtube")),
        FACEBOOK("fb", "फेसबुक", listOf("com.facebook.katana", "com.facebook.lite")),
    }

    fun appOf(pkg: String?): App? = if (pkg.isNullOrBlank()) null else App.values().firstOrNull { pkg in it.pkgs }

    /** स्क्रीन का एक हिस्सा: x/y = बीच का बिंदु, स्क्रीन के 0..1 हिस्से में */
    data class Node(val id: String, val text: String, val desc: String, val x: Float, val y: Float)

    private val ACTION = Regex("(?i)\\b(like|likes|liked|comment|comments|share|send|remix|dislike|reply)\\b|लाइक|पसंद|कमेंट|टिप्पणी|शेयर|भेज")
    private val YT_IDS = listOf("reel_", "shorts_")
    private val IG_IDS = listOf("clips_viewer", "clips_video", "clips_ufi", "clips_author", "clips_media", "reel_viewer")
    private val FB_IDS = listOf("reels_", "reel_", "video_player", "fullscreen_video")

    /** रील / शॉर्ट्स / वर्टिकल वीडियो वाली स्क्रीन? */
    fun reelMode(app: App, nodes: List<Node>): Boolean {
        val ids = when (app) { App.YOUTUBE -> YT_IDS; App.INSTAGRAM -> IG_IDS; App.FACEBOOK -> FB_IDS }
        if (nodes.any { n -> val id = n.id.substringAfter(":id/", n.id).lowercase(); ids.any { id.startsWith(it) || id.contains("_$it") } }) return true
        // दाईं तरफ़ ऊपर-नीचे बटन (लाइक/कमेंट/शेयर) = रील प्लेयर; फ़ीड में ये बटन एक लाइन में आड़े होते हैं
        val rail = nodes.filter { it.x > 0.78f && it.y in 0.35f..0.97f && ACTION.containsMatchIn(it.desc + " " + it.text) }
        return rail.size >= 3 && (rail.maxOf { it.y } - rail.minOf { it.y }) > 0.12f
    }

    private val UI_WORDS = Regex(
        "(?i)^(follow|following|subscribe|subscribed|like|likes|comment|comments|share|send|remix|more|less|see more|…|\\.\\.\\.|" +
            "original audio|sponsored|ad|reels|shorts|home|search|profile|create|सब्सक्राइब|फ़ॉलो|फॉलो|और देखें|और|शेयर|कमेंट|लाइक)$"
    )
    private val COUNTER = Regex("^[\\d.,\\s]+[KkMmBb]?$|^\\d+[:.]\\d+$|^[\\d.,]+\\s*(K|M|हज़ार|लाख|views|व्यूज़|likes|comments)?$", RegexOption.IGNORE_CASE)

    /** अभी कौन सी रील: नीचे-बाएँ का लिखा (नाम, caption, गाना) - गिनती वाले नंबर / बटन के शब्द नहीं */
    fun signature(nodes: List<Node>): Set<String> = nodes.asSequence()
        .filter { it.y in 0.50f..0.97f && it.x < 0.80f }
        .flatMap { sequenceOf(it.text, it.desc) }
        .map { it.replace(Regex("\\s+"), " ").trim().lowercase().take(60) }
        .filter { it.length >= 3 && !COUNTER.matches(it) && !UI_WORDS.matches(it) && !ACTION.matches(it) }
        .toSet()

    /** दो पहचान एक ही रील की? (आधे से ज़्यादा लिखा एक जैसा) */
    fun same(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.count { it in b }
        return inter * 2 > minOf(a.size, b.size)
    }

    /**
     * गिनने वाला: नई पहचान [stableMs] तक टिकी = +1. रील स्क्रीन से थोड़ी देर ([backGraceMs] से कम) हटे (कमेंट खोले)
     * और वही रील लौटी = +1 नहीं. ज़्यादा देर बाद लौटे = नई बार देखना = +1.
     */
    class Counter(private val stableMs: Long = 700, private val backGraceMs: Long = 4_000) {
        private var cur: Set<String>? = null
        private var pend: Set<String>? = null
        private var pendSince = 0L
        private var leftAt = 0L

        /** true = अभी एक रील गिनी गई */
        fun feed(inReel: Boolean, sig: Set<String>, now: Long): Boolean {
            if (!inReel) { if (leftAt == 0L) leftAt = now; pend = null; return false }
            if (leftAt > 0L && now - leftAt >= backGraceMs) cur = null
            if (sig.isEmpty()) return false
            val c = cur
            if (c != null && same(sig, c)) { pend = null; leftAt = 0L; cur = sig; return false }
            val p = pend
            if (p == null || !same(sig, p)) { pend = sig; pendSince = now; return false }
            if (now - pendSince >= stableMs) { cur = sig; pend = null; leftAt = 0L; return true }
            return false
        }

        fun reset() { cur = null; pend = null; pendSince = 0L; leftAt = 0L }
    }

    /** नई रील आई: (नई गिनती, अब रोकना है?). लिमिट 30 = 30 रील पूरी देख सकते हैं, 31वीं पर रोक (गिनती 30 ही) */
    fun onNewReel(count: Int, limit: Int): Pair<Int, Boolean> =
        if (limit > 0 && count >= limit) count to true else (count + 1) to false

    enum class Action(val id: String, val hi: String) {
        CLOSE("close", "वह ऐप बंद (होम पर) - और DK सेटिंग से खोलने तक वह ऐप न खुले"),
        LOCK("lock", "फ़ोन लॉक - और DK सेटिंग से खोलने तक वह ऐप न खुले");
        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: CLOSE }
    }

    /** ऊपर की छोटी पट्टी */
    fun pill(app: App, count: Int, limit: Int): String =
        "${app.hi} रील • आज $count" + (if (limit > 0) " / $limit" else "") + (if (limit > 0 && count >= limit) " • लिमिट पूरी" else "")

    fun blockedPill(app: App): String = "${app.hi}: आज की रील लिमिट पूरी • DK सेटिंग → रील गिनती और लिमिट से खोलें"
}
