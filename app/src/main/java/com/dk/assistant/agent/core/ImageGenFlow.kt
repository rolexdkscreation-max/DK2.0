package com.dk.assistant.agent.core

/**
 * v3.152 (मालिक): "DK, ChatGPT / Gemini में जाकर इमेज बनाओ, डाउनलोड करो और बताओ क्या दिख रहा है"।
 * यह pure हिस्सा (JUnit + FakeScreenDriver से जाँचा जाता है): असली ऐप खोलो -> लिखने का खाना -> prompt -> भेजो ->
 * इमेज बनने तक रुको (Stop / Creating image दिखे तब तक) -> नई बड़ी तस्वीर -> खोलो -> Download/Save -> गैलरी में पक्का।
 * तस्वीर में क्या है, यह ImageGenAgent (Android हिस्सा) बताता है - यहाँ नहीं।
 * लॉग-इन / CAPTCHA / इजाज़त वाली स्क्रीन पर रुकता है (BlockerDetector) - यूज़र खुद करे।
 */
enum class ImageApp(val hi: String, val pkg: String) {
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    GEMINI("Gemini", "com.google.android.apps.bard");

    companion object {
        fun parse(s: String?): ImageApp? {
            val t = s.orEmpty().lowercase()
            return when {
                t.contains("chat") || t.contains("gpt") || t.contains("openai") || t.contains("चैट") -> CHATGPT
                t.contains("gemini") || t.contains("जेमिनी") || t.contains("bard") -> GEMINI
                else -> null
            }
        }
    }
}

/** Android हिस्सा जो ScreenDriver में नहीं: ऐप खोलना, long-press, गैलरी में नई तस्वीर */
interface ImageGenHost {
    fun installed(pkg: String): Boolean
    suspend fun openApp(pkg: String): Boolean
    suspend fun longPress(x: Int, y: Int): Boolean
    /** start के बाद गैलरी में आई सबसे नई तस्वीर का नाम (नहीं = null / इजाज़त नहीं = null) */
    suspend fun newestImageSince(startMs: Long): String?
    fun now(): Long
}

data class ImageGenResult(
    val ok: Boolean,
    val app: ImageApp?,
    val downloaded: Boolean = false,
    val file: String? = null,
    /** ऐप ने तस्वीर की जगह जो लिखा (मना किया / सवाल पूछा) */
    val appReply: String = "",
    val blocker: Blocker? = null,
    val error: String = "",
    /** Download/Save दबाया (गैलरी में पक्का न हो पाया हो तब भी) */
    val pressedSave: Boolean = false,
)

class ImageGenFlow(
    private val d: ScreenDriver,
    private val host: ImageGenHost,
    private val pollMs: Long = 2_000,
    private val maxWaitMs: Long = 100_000,
) {
    companion object {
        const val UNVERIFIED = "\u0000unverified"
        val SEND = listOf("send", "send message", "send prompt", "submit", "भेजें", "भेजो", "संदेश भेजें")
        val STOP = listOf("stop generating", "stop response", "stop", "रोकें", "जवाब रोकें")
        val BUSY = listOf("creating image", "generating image", "generating", "making image", "creating", "इमेज बना", "इमेज बन", "बना रहा", "बनाई जा रही")
        val NEW_CHAT = listOf("new chat", "नई चैट", "नया चैट")
        val DOWNLOAD = listOf("download", "save image", "save to photos", "save to gallery", "save", "डाउनलोड", "सेव", "गैलरी में सेव", "सहेजें")
        val ALLOW = listOf("allow", "अनुमति दें", "allow access", "while using the app")
        val IMG_WORDS = listOf("image", "photo", "picture", "generated", "इमेज", "तस्वीर", "फ़ोटो", "फोटो")

        /** ऐप के हिसाब से साफ़ अनुरोध - ChatGPT/Gemini दोनों "Create an image" समझते हैं */
        fun promptFor(app: ImageApp, prompt: String): String {
            val p = prompt.trim().trimEnd('.', '।')
            return if (Regex("^(create|generate|make|draw)\\b.*\\b(image|picture|photo)", RegexOption.IGNORE_CASE).containsMatchIn(p)) p
            else "Create an image: $p"
        }

        /** लिखने का खाना - editable, सबसे नीचे वाला (ChatGPT/Gemini दोनों में नीचे) */
        fun input(o: ScreenObservation): UiElement? =
            o.elements.filter { it.editable && it.enabled && it.bounds.width > 0 }.maxByOrNull { it.bounds.bottom }

        fun sendButton(o: ScreenObservation): UiElement? = o.elements
            .filter { it.enabled && it.clickable && it.bounds.width > 0 && !it.editable }
            .filter { e -> val l = e.label; SEND.any { s -> l == s || (s.length >= 4 && l.contains(s)) } && !l.contains("voice") && !l.contains("आवाज़") }
            .maxByOrNull { it.bounds.bottom }

        fun busy(o: ScreenObservation): Boolean = o.elements.any { e ->
            val l = e.label
            (e.clickable && STOP.any { l == it || l.startsWith(it) }) || BUSY.any { l.contains(it) }
        }

        /** बड़ी तस्वीर (चौड़ाई ≥ 40%, ऊँचाई ≥ 15% स्क्रीन) - बनी हुई इमेज */
        fun images(o: ScreenObservation): List<UiElement> = o.elements.filter { e ->
            e.bounds.width >= o.width * 0.4 && e.bounds.height >= o.height * 0.15 && !e.editable &&
                (e.cls.contains("Image", true) || IMG_WORDS.any { e.desc.lowercase().contains(it) })
        }

        fun downloadButton(o: ScreenObservation): UiElement? = o.elements
            .filter { it.enabled && it.bounds.width > 0 && (it.clickable || it.desc.isNotBlank()) }
            .mapNotNull { e -> val l = e.label; DOWNLOAD.indexOfFirst { l == it || (it.length >= 4 && l.contains(it)) }.takeIf { it >= 0 }?.let { e to it } }
            .filter { (e, _) -> !e.label.contains("saved") && !e.label.contains("सेव हो") }
            .minByOrNull { it.second }?.first

        /** इमेज की जगह ऐप ने क्या लिखा - आख़िरी लंबा text (मना / सवाल) */
        fun lastReply(o: ScreenObservation, prompt: String): String = o.elements
            .filter { !it.editable && it.text.length >= 12 && !it.text.contains(prompt.take(20)) }
            .maxByOrNull { it.bounds.bottom }?.text?.take(300).orEmpty()
    }

    suspend fun run(want: ImageApp?, prompt: String, download: Boolean = true): ImageGenResult {
        if (prompt.isBlank()) return ImageGenResult(false, want, error = "क्या इमेज बनानी है, यह नहीं बताया")
        val order = listOfNotNull(want) + ImageApp.values().filter { it != want }
        val app = order.firstOrNull { host.installed(it.pkg) }
            ?: return ImageGenResult(false, want, error = "फ़ोन में ChatGPT या Gemini ऐप नहीं है - Play Store से इंस्टॉल करके एक बार लॉग-इन कर लीजिए")
        val start = host.now()
        if (!host.openApp(app.pkg)) return ImageGenResult(false, app, error = "${app.hi} ऐप नहीं खुला")

        // 1) लिखने का खाना
        var obs: ScreenObservation? = null
        var box: UiElement? = null
        for (i in 0 until 10) {
            val o = d.capture()
            if (o != null) {
                BlockerDetector.detect(o)?.let { return ImageGenResult(false, app, blocker = it, error = "${app.hi} में ${it.hi} - पहले आप खुद पूरा कीजिए") }
                if (i == 0 && newChat(o) != null) { d.pause(900); continue }
                input(o)?.let { obs = o; box = it }
                if (box != null) break
            }
            d.pause(800)
        }
        val o0 = obs; val b0 = box
        if (o0 == null || b0 == null) return ImageGenResult(false, app, error = "${app.hi} में लिखने की जगह नहीं मिली (शायद लॉग-इन करना है)")
        val before = images(o0).size
        val text = promptFor(app, prompt)

        // 2) लिखो + भेजो (भेजने का बटन लिखने के बाद ही आता है)
        if (d.setText(o0, b0, text) != DriverResult.OK) return ImageGenResult(false, app, error = "${app.hi} में prompt नहीं लिख पाया")
        d.pause(500)
        var sent = false
        for (i in 0 until 4) {
            val o = d.capture() ?: continue
            val s = sendButton(o)
            if (s != null) {
                if (d.click(o, s) != DriverResult.OK) d.tapAt(s.bounds.centerX, s.bounds.centerY)
                sent = true; break
            }
            d.pause(500)
        }
        if (!sent) return ImageGenResult(false, app, error = "${app.hi} में भेजने का बटन नहीं मिला")

        // 3) बनने तक रुको
        var waited = 0L
        var last: ScreenObservation? = null
        var idle = 0
        var got: UiElement? = null
        while (waited < maxWaitMs) {
            d.pause(pollMs); waited += pollMs
            val o = d.capture() ?: continue
            last = o
            BlockerDetector.detect(o)?.let { return ImageGenResult(false, app, blocker = it, error = "${app.hi} में ${it.hi}") }
            val imgs = images(o)
            val isBusy = busy(o)
            if (imgs.size > before && !isBusy) { got = imgs.maxByOrNull { it.bounds.bottom }; break }
            idle = if (isBusy) 0 else idle + 1
            // 20s+ से कुछ नहीं बन रहा और नई तस्वीर नहीं = ऐप ने मना किया / सवाल पूछा
            if (waited >= 20_000 && idle >= 4) break
        }
        val img = got
            ?: return ImageGenResult(false, app, appReply = last?.let { lastReply(it, prompt) }.orEmpty(),
                error = if (waited >= maxWaitMs) "${app.hi} ने ${maxWaitMs / 1000} सेकंड में इमेज नहीं बनाई" else "${app.hi} ने इमेज नहीं बनाई")
        if (!download) return ImageGenResult(true, app)

        // 4) डाउनलोड: तस्वीर खोलो -> Download/Save; न मिले तो long-press वाला menu
        val saved = downloadImage(img, start)
        return ImageGenResult(true, app, downloaded = saved != null && saved != UNVERIFIED, file = saved?.takeIf { it != UNVERIFIED }, pressedSave = saved != null)
    }

    private suspend fun newChat(o: ScreenObservation): UiElement? {
        val n = o.elements.firstOrNull { e -> e.clickable && e.enabled && NEW_CHAT.any { e.label == it || e.label.startsWith(it) } } ?: return null
        return if (d.click(o, n) == DriverResult.OK) n else null
    }

    private suspend fun downloadImage(img: UiElement, start: Long): String? {
        // पहले सीधा उसी स्क्रीन पर Download बटन (Gemini तस्वीर के नीचे देता है)
        for (attempt in 0 until 3) {
            val o = d.capture() ?: return null
            val btn = downloadButton(o)
            when {
                btn != null -> { if (d.click(o, btn) != DriverResult.OK) d.tapAt(btn.bounds.centerX, btn.bounds.centerY) }
                attempt == 0 -> { val t = images(o).maxByOrNull { it.bounds.bottom } ?: img; if (d.click(o, t) != DriverResult.OK) d.tapAt(t.bounds.centerX, t.bounds.centerY); d.pause(1_200); continue }
                attempt == 1 -> { host.longPress(img.bounds.centerX, img.bounds.centerY); d.pause(900); continue }
                else -> return null
            }
            // इजाज़त माँगे (फ़ोटो सेव) = मालिक ने खुद डाउनलोड कहा है, Allow
            for (i in 0 until 8) {
                d.pause(700)
                val p = d.capture() ?: continue
                p.elements.firstOrNull { e -> e.clickable && ALLOW.any { e.label == it || e.label.startsWith(it) } }?.let { d.click(p, it) }
                host.newestImageSince(start)?.let { return it }
            }
            return host.newestImageSince(start) ?: UNVERIFIED
        }
        return null
    }
}
