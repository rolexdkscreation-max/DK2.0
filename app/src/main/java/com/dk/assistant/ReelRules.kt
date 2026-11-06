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

    /** स्क्रीन का एक हिस्सा: x/y = बीच का बिंदु, w/h = चौड़ाई / ऊँचाई - सब स्क्रीन के 0..1 हिस्से में (w/h 0 = नाप पता नहीं) */
    data class Node(val id: String, val text: String, val desc: String, val x: Float, val y: Float, val w: Float = 0f, val h: Float = 0f) {
        val sized get() = w > 0f || h > 0f
    }

    /*
     * नवंबर 2026 (मालिक): "फ़ीड स्क्रॉल करें तो गिनती नहीं - YouTube / फेसबुक / इंस्टाग्राम कहीं भी. सिर्फ़ जब रील / वीडियो पर
     * टैप करके देखना शुरू करें और वह चले, तब गिनो." इसलिए: रील का view id अब तभी माना जाता है जब वह पूरी स्क्रीन जितना बड़ा
     * हो (फ़ीड में चलती छोटी वीडियो / शॉर्ट्स की कतार = नहीं), और गिनती तब जब वह रील [WATCH_MS] तक चली (स्क्रॉल में निकल गई = नहीं).
     */
    const val WATCH_MS = 1_500L

    /** पूरी स्क्रीन वाला प्लेयर (नाप पता न हो तो id पर भरोसा) */
    private fun fullScreen(n: Node) = !n.sized || (n.w >= 0.8f && n.h >= 0.6f)

    private val ACTION = Regex("(?i)\\b(like|likes|liked|comment|comments|share|send|remix|dislike|reply)\\b|लाइक|पसंद|कमेंट|टिप्पणी|शेयर|भेज")
    private val YT_IDS = listOf("reel_", "shorts_")
    private val IG_IDS = listOf("clips_viewer", "clips_video", "clips_ufi", "clips_author", "clips_media", "reel_viewer")
    private val FB_IDS = listOf("reels_", "reel_", "video_player", "fullscreen_video")

    /** रील / शॉर्ट्स / वर्टिकल वीडियो वाली स्क्रीन? */
    fun reelMode(app: App, nodes: List<Node>): Boolean {
        val ids = when (app) { App.YOUTUBE -> YT_IDS; App.INSTAGRAM -> IG_IDS; App.FACEBOOK -> FB_IDS }
        if (nodes.any { n -> val id = n.id.substringAfter(":id/", n.id).lowercase(); ids.any { id.startsWith(it) || id.contains("_$it") } && fullScreen(n) }) return true
        // दाईं तरफ़ ऊपर-नीचे बटन (लाइक/कमेंट/शेयर) = रील प्लेयर; फ़ीड में ये बटन एक लाइन में आड़े होते हैं
        val rail = nodes.filter { it.x > 0.78f && it.y in 0.35f..0.97f && ACTION.containsMatchIn(it.desc + " " + it.text) }
        return rail.size >= 3 && (rail.maxOf { it.y } - rail.minOf { it.y }) > 0.12f
    }

    /*
     * नवंबर 2026 (मालिक: "रील या वीडियो, कहीं भी देखें, गिनती हो"): YouTube का आम (लंबा) वीडियो भी - वीडियो वाला पेज
     * (player का view id) और पहचान = player के नीचे का शीर्षक / चैनल. वही वीडियो चलता रहा = +1 नहीं.
     */
    private val YT_WATCH = listOf("watch_player", "player_view", "watch_while", "player_fragment")

    private val WATCH_PAGE = Regex("(?i)^(subscribe|subscribed|सब्सक्राइब|सदस्यता लें)$|dislike|नापसंद")

    /**
     * YouTube का आम वीडियो वाला पेज (शॉर्ट्स नहीं) - टैप करके खुला वीडियो: player पूरी चौड़ाई का (नीचे का छोटा mini player नहीं)
     * और उसी पेज पर Subscribe / Dislike (होम फ़ीड में चलती वीडियो पर ये नहीं होते), या पूरी स्क्रीन (आड़ा) वीडियो.
     */
    fun watchMode(app: App, nodes: List<Node>): Boolean {
        if (app != App.YOUTUBE || reelMode(app, nodes)) return false
        val players = nodes.filter { n -> val id = n.id.substringAfter(":id/", n.id).lowercase(); YT_WATCH.any { id.startsWith(it) } }
        if (players.any { it.sized && it.w >= 0.8f && it.h >= 0.8f }) return true                       // पूरी स्क्रीन वीडियो
        if (players.none { !it.sized || it.w >= 0.9f }) return false                                    // mini player / फ़ीड का छोटा
        return nodes.any { WATCH_PAGE.containsMatchIn(it.text.trim()) || WATCH_PAGE.containsMatchIn(it.desc.trim()) }
    }

    /** आम वीडियो की पहचान: player के नीचे का लिखा (शीर्षक, चैनल) */
    fun watchSignature(nodes: List<Node>): Set<String> = signatureIn(nodes, 0.22f..0.62f, 1.01f)

    private val UI_WORDS = Regex(
        "(?i)^(follow|following|subscribe|subscribed|like|likes|comment|comments|share|send|remix|more|less|see more|…|\\.\\.\\.|" +
            "original audio|sponsored|ad|reels|shorts|home|search|profile|create|सब्सक्राइब|फ़ॉलो|फॉलो|और देखें|और|शेयर|कमेंट|लाइक)$"
    )
    private val COUNTER = Regex("^[\\d.,\\s]+[KkMmBb]?$|^\\d+[:.]\\d+$|^[\\d.,]+\\s*(K|M|हज़ार|लाख|views|व्यूज़|likes|comments)?$", RegexOption.IGNORE_CASE)

    /** अभी कौन सी रील: नीचे-बाएँ का लिखा (नाम, caption, गाना) - गिनती वाले नंबर / बटन के शब्द नहीं */
    fun signature(nodes: List<Node>): Set<String> = signatureIn(nodes, 0.50f..0.97f, 0.80f)

    private fun signatureIn(nodes: List<Node>, ys: ClosedFloatingPointRange<Float>, maxX: Float): Set<String> = nodes.asSequence()
        .filter { it.y in ys && it.x < maxX }
        .flatMap { sequenceOf(it.text, it.desc) }
        .map { it.replace(Regex("\\s+"), " ").trim().lowercase().take(60) }
        .filter { it.length >= 3 && !COUNTER.matches(it) && !UI_WORDS.matches(it) && !ACTION.matches(it) && it.count(Char::isDigit) * 5 < it.length * 2 }   // "3:45 / 10:20" जैसा चलता समय नहीं
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

    /**
     * YouTube की पट्टी (मालिक: "शॉर्ट्स हो तो शॉर्ट्स लिखा, वीडियो हो तो वीडियो - कितने वीडियो दिखे वह अलग").
     * लिमिट = YouTube का कुल (शॉर्ट्स + वीडियो).
     */
    fun ytPill(video: Boolean, shorts: Int, videos: Int, limit: Int): String {
        val total = shorts + videos
        val main = if (video) "YouTube वीडियो • आज $videos" + (if (shorts > 0) " • शॉर्ट्स $shorts" else "")
            else "YouTube शॉर्ट्स • आज $shorts" + (if (videos > 0) " • वीडियो $videos" else "")
        return main + (if (limit > 0) " • कुल $total / $limit" else "") + (if (limit > 0 && total >= limit) " • लिमिट पूरी" else "")
    }

    fun blockedPill(app: App): String = "${app.hi}: आज की रील लिमिट पूरी • DK सेटिंग → रील गिनती और लिमिट से खोलें"
}
