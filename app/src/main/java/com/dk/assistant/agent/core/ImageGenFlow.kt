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

/** Android हिस्सा जो ScreenDriver में नहीं: ऐप खोलना, long-press, गैलरी में नई तस्वीर, स्क्रीन देखना */
interface ImageGenHost {
    fun installed(pkg: String): Boolean
    suspend fun openApp(pkg: String): Boolean
    suspend fun longPress(x: Int, y: Int): Boolean
    /** start के बाद गैलरी में आई सबसे नई तस्वीर का नाम (नहीं = null / इजाज़त नहीं = null) */
    suspend fun newestImageSince(startMs: Long): String?
    fun now(): Long
    /** v3.153: keyboard का Enter/भेजो (लिखने वाले खाने पर) */
    suspend fun imeEnter(): Boolean = false
    /** v3.153: स्क्रीन देखकर (screenshot + Gemini vision) चीज़ कहाँ है - स्क्रीन के pixel (x, y); न दिखे = null */
    suspend fun look(goal: String): Pair<Int, Int>? = null
    /** स्क्रीन देखना चालू है (मालिक ने "स्क्रीन देखना" ऑन किया + Android 11+) */
    fun canLook(): Boolean = false
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
    /** गड़बड़ी पर स्क्रीन की छोटी सूची (DK log में - बाद में ठीक करने को) */
    val screenDump: String = "",
    /** कौन-से तरीके से काम हुआ (log) */
    val how: String = "",
)

class ImageGenFlow(
    private val d: ScreenDriver,
    private val host: ImageGenHost,
    private val pollMs: Long = 2_000,
    private val maxWaitMs: Long = 120_000,
) {
    companion object {
        const val UNVERIFIED = "\u0000unverified"
        val SEND = listOf("send", "send message", "send prompt", "submit", "भेजें", "भेजो", "संदेश भेजें")
        val STOP = listOf("stop generating", "stop response", "stop", "रोकें", "जवाब रोकें")
        val BUSY = listOf("creating image", "generating image", "generating", "making image", "creating", "इमेज बना", "इमेज बन", "बना रहा", "बनाई जा रही")
        val NEW_CHAT = listOf("new chat", "नई चैट", "नया चैट")
        val DOWNLOAD = listOf("download", "save image", "save to photos", "save to gallery", "save", "डाउनलोड", "सेव", "गैलरी में सेव", "सहेजें")
        val MORE = listOf("more options", "more", "options", "menu", "और विकल्प", "ज़्यादा विकल्प")
        val ALLOW = listOf("allow", "अनुमति दें", "allow access", "while using the app")
        val IMG_WORDS = listOf("image", "photo", "picture", "generated", "इमेज", "तस्वीर", "फ़ोटो", "फोटो")
        /** भेजने की जगह ये बटन कभी नहीं (बोलने वाला / फ़ाइल जोड़ने वाला) */
        val NOT_SEND = listOf("voice", "mic", "dictat", "speak", "audio", "आवाज़", "बोल", "attach", "add", "plus", "upload", "camera", "photo", "file",
            "tool", "image", "gallery", "menu", "more", "option", "share", "copy", "edit", "+", "जोड़", "कैमरा", "फ़ाइल")

        const val LOOK_SEND = "the SEND button (usually an arrow icon at the right end of the message box) that sends the typed message. NOT the microphone / voice / plus / attach button"
        const val LOOK_IMAGE = "the newly generated picture (the big image the AI just made, in the chat)"
        const val LOOK_DOWNLOAD = "the DOWNLOAD or SAVE button / icon for the picture (often a downward arrow, or 'Save', 'Download' in a menu)"

        /** ऐप के हिसाब से साफ़ अनुरोध - ChatGPT/Gemini दोनों "Create an image" समझते हैं */
        fun promptFor(app: ImageApp, prompt: String): String {
            val p = prompt.trim().trimEnd('.', '।')
            return if (Regex("^(create|generate|make|draw)\\b.*\\b(image|picture|photo)", RegexOption.IGNORE_CASE).containsMatchIn(p)) p
            else "Create an image: $p"
        }

        /** लिखने का खाना - editable, सबसे नीचे वाला (ChatGPT/Gemini दोनों में नीचे) */
        fun input(o: ScreenObservation): UiElement? =
            o.elements.filter { it.editable && it.enabled && it.bounds.width > 0 }.maxByOrNull { it.bounds.bottom }

        private fun notSend(l: String) = NOT_SEND.any { l.contains(it) }

        /** नाम से भेजो बटन - clickable न भी हो (Compose में नाम अंदर वाले icon पर होता है; click ऊपर वाले बटन पर जाता है) */
        fun sendButton(o: ScreenObservation): UiElement? = o.elements
            .filter { it.enabled && it.bounds.width > 0 && !it.editable }
            .filter { e -> val l = e.label; SEND.any { s -> l == s || (s.length >= 4 && l.contains(s)) } && !l.contains("voice") && !l.contains("आवाज़") }
            .sortedWith(compareByDescending<UiElement> { it.clickable }.thenByDescending { it.bounds.bottom })
            .firstOrNull()

        /** लिखने के बाद नया आया बटन, लिखने वाले खाने की पंक्ति में / दाईं ओर (ChatGPT का ↑ तीर - बिना नाम) */
        fun newButtonAfterTyping(before: ScreenObservation, after: ScreenObservation): UiElement? {
            val box = input(after) ?: input(before) ?: return null
            val old = before.elements.filter { it.clickable }.map { it.label to it.bounds }.toSet()
            return after.elements.filter { it.clickable && it.enabled && !it.editable && (it.label to it.bounds) !in old && !notSend(it.label) && nearRow(it, box, after) }
                .maxByOrNull { it.bounds.right }
        }

        /** लिखने वाले खाने की पंक्ति का सबसे दाईं ओर का बटन (आख़िरी उपाय) */
        fun rightmostInRow(o: ScreenObservation): UiElement? {
            val box = input(o) ?: return null
            return o.elements.filter { it.clickable && it.enabled && !it.editable && !notSend(it.label) && nearRow(it, box, o) && it.bounds.centerX > box.bounds.centerX }
                .maxByOrNull { it.bounds.right }
        }

        private fun nearRow(e: UiElement, box: UiElement, o: ScreenObservation): Boolean {
            val pad = (o.height * 0.06).toInt()
            return e.bounds.centerY in (box.bounds.top - pad)..(box.bounds.bottom + pad) && e.bounds.width < o.width / 3
        }

        /**
         * v3.156: भेजें बटन न नाम से मिले न ढाँचे में - लिखने वाले खाने की पंक्ति के दाएँ सिरे की जगहें.
         * उस जगह पर आवाज़ / जोड़ो (+) / कैमरा वाला बटन हो तो वहाँ कभी नहीं.
         */
        fun blindSendPoints(o: ScreenObservation): List<Pair<Int, Int>> {
            val box = input(o) ?: return emptyList()
            val b = box.bounds
            val h = (b.bottom - b.top).coerceIn(60, 200)
            val y = b.bottom - h / 2
            val pts = listOf(b.right - h / 2 to y, b.right + h / 2 to y, o.width - h / 2 to y)
            return pts.filter { (x, _) -> x in 1 until o.width }.distinct().filter { (x, py) ->
                o.elements.none { e -> !e.editable && notSend(e.label) && e.label.isNotBlank() &&
                    x in e.bounds.left..e.bounds.right && py in e.bounds.top..e.bounds.bottom }
            }
        }

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
            .filter { it.enabled && it.bounds.width > 0 && !it.editable && (it.clickable || it.desc.isNotBlank() || it.text.isNotBlank()) }
            .mapNotNull { e -> val l = e.label; DOWNLOAD.indexOfFirst { l == it || (it.length >= 4 && l.contains(it)) }.takeIf { it >= 0 }?.let { e to it } }
            .filter { (e, _) -> !e.label.contains("saved") && !e.label.contains("सेव हो") && e.label.length <= 40 }
            .minByOrNull { it.second }?.first

        fun moreButton(o: ScreenObservation): UiElement? = o.elements
            .firstOrNull { e -> e.enabled && e.clickable && e.label.length <= 25 && MORE.any { e.label == it || e.label.startsWith(it) } }

        /** इमेज की जगह ऐप ने क्या लिखा - आख़िरी लंबा text (मना / सवाल) */
        fun lastReply(o: ScreenObservation, prompt: String): String = o.elements
            .filter { !it.editable && it.text.length >= 12 && !it.text.contains(prompt.take(20)) }
            .maxByOrNull { it.bounds.bottom }?.text?.take(300).orEmpty()

        /** v3.156: सवाल का पूरा जवाब - आपके संदेश के नीचे और लिखने वाले खाने के ऊपर के सारे लिखे हिस्से */
        fun answerText(o: ScreenObservation, prompt: String): String {
            val head = prompt.trim().take(20)
            val mine = o.elements.filter { !it.editable && head.isNotEmpty() && it.text.contains(head) }.maxByOrNull { it.bounds.bottom }
            val boxTop = input(o)?.bounds?.top ?: o.height
            val from = mine?.bounds?.bottom ?: 0
            val parts = o.elements.filter { e ->
                !e.editable && e.text.isNotBlank() && e.bounds.top >= from && e.bounds.bottom <= boxTop &&
                    !(e.clickable && e.text.length < 25) && (mine == null || !e.text.contains(head))
            }.map { it.text.trim() }.distinct()
            val all = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            return if (all.length >= 2) all.take(1500) else lastReply(o, prompt)
        }

        /** DK log के लिए स्क्रीन की छोटी सूची */
        fun dump(o: ScreenObservation?): String = o?.elements?.take(40)?.joinToString("; ") { e ->
            "${e.cls}${if (e.clickable) "*" else ""}${if (e.editable) "E" else ""}'${e.label.take(30)}'@${e.bounds.centerX},${e.bounds.centerY}"
        }.orEmpty()
    }

    private val how = mutableListOf<String>()

    /** खोलो + लिखो + भेजो - इमेज और सवाल (ask) दोनों में वही रास्ता */
    private class Sent(val app: ImageApp, val o0: ScreenObservation?, val fail: ImageGenResult?)

    private suspend fun openTypeSend(want: ImageApp?, prompt: String, image: Boolean): Sent {
        val order = listOfNotNull(want) + ImageApp.values().filter { it != want }
        val app = order.firstOrNull { host.installed(it.pkg) }
            ?: return Sent(want ?: ImageApp.CHATGPT, null, ImageGenResult(false, want, error = "फ़ोन में ChatGPT या Gemini ऐप नहीं है - Play Store से इंस्टॉल करके एक बार लॉग-इन कर लीजिए"))
        if (!host.openApp(app.pkg)) return Sent(app, null, ImageGenResult(false, app, error = "${app.hi} ऐप नहीं खुला"))

        // 1) लिखने का खाना (न मिले तो स्क्रीन देखकर)
        var obs: ScreenObservation? = null
        var box: UiElement? = null
        var lastSeen: ScreenObservation? = null
        for (i in 0 until 10) {
            val o = d.capture()
            if (o != null) {
                lastSeen = o
                BlockerDetector.detect(o)?.let { return Sent(app, null, ImageGenResult(false, app, blocker = it, error = "${app.hi} में ${it.hi} - पहले आप खुद पूरा कीजिए")) }
                if (i == 0 && newChat(o) != null) { d.pause(900); continue }
                input(o)?.let { obs = o; box = it }
                if (box != null) break
                if (i == 3 && host.canLook()) {
                    host.look("the message / prompt input box where you type")?.let { (x, y) -> how += "input:look"; d.tapAt(x, y); d.pause(700) }
                }
            }
            d.pause(800)
        }
        val o0 = obs; val b0 = box
        if (o0 == null || b0 == null) return Sent(app, null, fail(app, "${app.hi} में लिखने की जगह नहीं मिली (शायद लॉग-इन करना है)", lastSeen))
        val text = if (image) promptFor(app, prompt) else prompt.trim()

        // 2) लिखो + भेजो
        if (d.setText(o0, b0, text) != DriverResult.OK) return Sent(app, o0, fail(app, "${app.hi} में prompt नहीं लिख पाया", o0))
        d.pause(600)
        if (!send(o0, text)) return Sent(app, o0, fail(app, "${app.hi} में prompt भेज नहीं पाया - भेजने का बटन न नाम से मिला, न जगह से" +
            if (!host.canLook()) " (DK स्क्रीन देखकर भी दबा सकता है - इसके लिए \"स्क्रीन देखना चालू करो\" बोलिए)" else " (स्क्रीन देखकर भी नहीं मिला)", d.capture()))
        return Sent(app, o0, null)
    }

    /**
     * v3.156 (मालिक): ChatGPT / Gemini से कुछ भी पूछो (इमेज नहीं) - लिखो, भेजो, जवाब पूरा होने तक रुको, जवाब पढ़कर लौटाओ.
     */
    suspend fun ask(want: ImageApp?, prompt: String): ImageGenResult {
        if (prompt.isBlank()) return ImageGenResult(false, want, error = "क्या पूछना / लिखना है, यह नहीं बताया")
        val s = openTypeSend(want, prompt, image = false)
        s.fail?.let { return it }
        val app = s.app
        var waited = 0L; var prev = ""; var same = 0; var last: ScreenObservation? = null; var sawBusy = false
        while (waited < maxWaitMs) {
            d.pause(pollMs); waited += pollMs
            val o = d.capture() ?: continue
            last = o
            BlockerDetector.detect(o)?.let { return ImageGenResult(false, app, blocker = it, error = "${app.hi} में ${it.hi}") }
            if (busy(o)) { sawBusy = true; same = 0; continue }
            val rep = answerText(o, prompt)
            same = if (rep.isNotBlank() && rep == prev) same + 1 else 0
            prev = rep
            // जवाब लिखना बंद (2 बार वही) = पूरा
            if (rep.isNotBlank() && same >= 2 && (sawBusy || waited >= pollMs * 3)) break
        }
        val reply = last?.let { answerText(it, prompt) }.orEmpty()
        return if (reply.isNotBlank()) ImageGenResult(true, app, appReply = reply, how = how.joinToString())
        else ImageGenResult(true, app, how = how.joinToString(), screenDump = dump(last), error = "भेज दिया, पर जवाब पढ़ नहीं पाया")
    }

    suspend fun run(want: ImageApp?, prompt: String, download: Boolean = true): ImageGenResult {
        if (prompt.isBlank()) return ImageGenResult(false, want, error = "क्या इमेज बनानी है, यह नहीं बताया")
        val start = host.now()
        val s = openTypeSend(want, prompt, image = true)
        s.fail?.let { return it }
        val app = s.app
        val before = s.o0?.let { images(it).size } ?: 0

        // 3) बनने तक रुको - इमेज बनने में 30-90 सेकंड; "बना रहा है" न दिखे तब भी रुकते हैं
        var waited = 0L
        var last: ScreenObservation? = null
        var got: UiElement? = null
        var prevReply = ""
        var sameReply = 0
        var looked = 0
        while (waited < maxWaitMs) {
            d.pause(pollMs); waited += pollMs
            val o = d.capture() ?: continue
            last = o
            BlockerDetector.detect(o)?.let { return ImageGenResult(false, app, blocker = it, error = "${app.hi} में ${it.hi}") }
            val imgs = images(o)
            val isBusy = busy(o)
            if (imgs.size > before && !isBusy) { got = imgs.maxByOrNull { it.bounds.bottom }; break }
            if (isBusy) { sameReply = 0; continue }
            val rep = lastReply(o, prompt)
            sameReply = if (rep.isNotBlank() && rep == prevReply) sameReply + 1 else 0
            prevReply = rep
            // ढाँचे में तस्वीर नहीं दिखती (कुछ ऐप में बिना नाम) - बीच-बीच में स्क्रीन देखकर
            if (host.canLook() && waited >= 25_000 && looked < 3 && waited % (pollMs * 6) == 0L) {
                looked++
                host.look(LOOK_IMAGE)?.let { (x, y) -> how += "image:look"; got = UiElement(-1, "", "generated image", cls = "Image", bounds = Box(x - 150, y - 150, x + 150, y + 150)) }
                if (got != null) break
            }
            // जवाब 10s+ से वही है, कुछ बन नहीं रहा, और 30s हो गए = ऐप ने मना किया / सवाल पूछा
            if (waited >= 30_000 && sameReply >= 5) break
        }
        if (got == null && host.canLook()) host.look(LOOK_IMAGE)?.let { (x, y) ->
            how += "image:look-end"; got = UiElement(-1, "", "generated image", cls = "Image", bounds = Box(x - 150, y - 150, x + 150, y + 150))
        }
        val img = got
            ?: return ImageGenResult(false, app, appReply = last?.let { lastReply(it, prompt) }.orEmpty(), screenDump = dump(last), how = how.joinToString(),
                error = if (waited >= maxWaitMs) "${app.hi} ने ${maxWaitMs / 1000} सेकंड में इमेज नहीं बनाई (हो सकता है ऐप में अभी भी बन रही हो)" else "${app.hi} ने इमेज नहीं बनाई")
        if (!download) return ImageGenResult(true, app, how = how.joinToString())

        // 4) डाउनलोड
        val saved = downloadImage(img, start)
        return ImageGenResult(true, app, downloaded = saved != null && saved != UNVERIFIED, file = saved?.takeIf { it != UNVERIFIED }, pressedSave = saved != null,
            screenDump = if (saved == null) dump(d.capture()) else "", how = how.joinToString())
    }

    private fun fail(app: ImageApp, msg: String, o: ScreenObservation?) = ImageGenResult(false, app, error = msg, screenDump = dump(o), how = how.joinToString())

    private suspend fun press(o: ScreenObservation, e: UiElement) {
        if (d.click(o, e) != DriverResult.OK) d.tapAt(e.bounds.centerX, e.bounds.centerY)
    }

    /** भेजा गया? - "बना रहा है" / आपका संदेश chat में आ गया / लिखने वाला खाना खाली हुआ */
    private suspend fun sentOk(text: String, typedVisible: Boolean): Boolean {
        for (i in 0 until 3) {
            d.pause(700)
            val o = d.capture() ?: continue
            if (busy(o)) return true
            val head = text.take(20)
            if (o.elements.any { !it.editable && it.text.contains(head) }) return true
            val box = input(o) ?: return true
            if (typedVisible && !box.text.contains(head)) return true
        }
        return false
    }

    /** भेजने के कई तरीके, हर एक के बाद पक्का करो कि सच में गया (दो बार न जाए) */
    private suspend fun send(o0: ScreenObservation, text: String): Boolean {
        val o = d.capture() ?: return false
        val typedVisible = input(o)?.text?.contains(text.take(20)) == true
        sendButton(o)?.let { if (pressSent(o, it, text, typedVisible)) { how += "send:label"; return true } }
        d.capture()?.let { o2 -> newButtonAfterTyping(o0, o2)?.let { if (pressSent(o2, it, text, typedVisible)) { how += "send:new-button"; return true } } }
        if (host.imeEnter()) { how += "send:enter"; if (sentOk(text, typedVisible)) return true }
        if (host.canLook()) host.look(LOOK_SEND)?.let { (x, y) -> d.tapAt(x, y); how += "send:look"; if (sentOk(text, typedVisible)) return true }
        d.capture()?.let { o3 -> rightmostInRow(o3)?.let { if (pressSent(o3, it, text, typedVisible)) { how += "send:rightmost"; return true } } }
        // v3.156: आख़िरी उपाय - लिखने वाले खाने के दाएँ सिरे पर उँगली से टैप (ChatGPT का ↑ तीर अक्सर बिना नाम/ढाँचे के)
        d.capture()?.let { o4 ->
            for ((x, y) in blindSendPoints(o4)) {
                if (d.tapAt(x, y) == DriverResult.OK) { how += "send:edge-tap"; if (sentOk(text, typedVisible)) return true }
            }
        }
        return false
    }

    /** बटन दबाओ; "दबा" बोले पर भेजा न जाए (Compose में click कहीं और जाता है) तो उसी जगह उँगली से टैप */
    private suspend fun pressSent(o: ScreenObservation, e: UiElement, text: String, typedVisible: Boolean): Boolean {
        val clicked = d.click(o, e) == DriverResult.OK
        if (clicked && sentOk(text, typedVisible)) return true
        if (d.tapAt(e.bounds.centerX, e.bounds.centerY) != DriverResult.OK) return false
        how += "tap"
        return sentOk(text, typedVisible)
    }

    private suspend fun newChat(o: ScreenObservation): UiElement? {
        val n = o.elements.firstOrNull { e -> e.clickable && e.enabled && NEW_CHAT.any { e.label == it || e.label.startsWith(it) } } ?: return null
        return if (d.click(o, n) == DriverResult.OK) n else null
    }

    /** तस्वीर खोलो -> Download/Save; न मिले तो ⋮ menu, long-press menu, फिर स्क्रीन देखकर */
    private suspend fun downloadImage(img: UiElement, start: Long): String? {
        var opened = false; var menu = false; var longPressed = false; var looked = false
        for (attempt in 0 until 7) {
            val o = d.capture() ?: return null
            val btn = downloadButton(o)
            if (btn != null) { press(o, btn); how += "download:label"; return confirmSave(start) }
            val cur = images(o).maxByOrNull { it.bounds.bottom } ?: img
            when {
                !opened -> { opened = true; press(o, cur); d.pause(1_200) }
                !menu && moreButton(o) != null -> { menu = true; moreButton(o)?.let { press(o, it) }; d.pause(800) }
                !longPressed -> { longPressed = true; host.longPress(cur.bounds.centerX, cur.bounds.centerY); d.pause(900) }
                !looked && host.canLook() -> {
                    looked = true
                    val pt = host.look(LOOK_DOWNLOAD) ?: return null
                    d.tapAt(pt.first, pt.second); how += "download:look"
                    return confirmSave(start)
                }
                else -> return null
            }
        }
        return null
    }

    /** इजाज़त माँगे (फ़ोटो सेव) = मालिक ने खुद डाउनलोड कहा है, Allow; फिर गैलरी में पक्का */
    private suspend fun confirmSave(start: Long): String {
        for (i in 0 until 8) {
            d.pause(700)
            val p = d.capture() ?: continue
            p.elements.firstOrNull { e -> e.clickable && ALLOW.any { e.label == it || e.label.startsWith(it) } }?.let { d.click(p, it) }
            host.newestImageSince(start)?.let { return it }
        }
        return host.newestImageSince(start) ?: UNVERIFIED
    }
}
