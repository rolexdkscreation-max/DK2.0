package com.dk.assistant.agent.core

/**
 * 28 नवंबर 2026 (मालिक: "Gemini पर जाकर वीडियो बनाओ, भाग-भाग में, किरदार वही"): एक भाग (8 सेकंड) Gemini ऐप में.
 * pure (FakeScreenDriver से जाँचा जाता है): वही chat -> prompt लिखो + भेजो -> "Generating video" खत्म होने तक रुको (कुछ मिनट) ->
 * नया वीडियो -> Download -> फ़ोन में पक्का (गैलरी) -> वापस chat पर (अगला भाग उसी बातचीत में).
 * Limit का जवाब ("try again later", "limit") = रुको और सच बताओ. लॉग-इन / CAPTCHA = मालिक खुद करे.
 */
data class VideoClipResult(
    val ok: Boolean,
    /** फ़ोन में आया वीडियो (गैलरी का नाम / uri). Download दबाया पर पक्का न हुआ = [ImageGenFlow.UNVERIFIED] */
    val file: String? = null,
    val blocker: Blocker? = null,
    /** Gemini ने कहा अभी और वीडियो नहीं (limit / बाद में) */
    val limit: Boolean = false,
    /** वीडियो की जगह ऐप ने जो लिखा */
    val reply: String = "",
    val error: String = "",
    val how: String = "",
)

class VideoGenFlow(
    private val d: ScreenDriver,
    private val host: ImageGenHost,
    private val pollMs: Long = 3_000,
    private val maxWaitMs: Long = 6 * 60_000L,
) {
    companion object {
        val VIDEO_BUSY = listOf("generating video", "generating your video", "creating video", "creating your video", "making your video",
            "video is being", "this may take", "few minutes", "a minute or two", "वीडियो बन", "वीडियो बना रह", "वीडियो तैयार हो रहा", "कुछ मिनट")
        val LIMIT = listOf("limit", "try again later", "come back later", "can't create more", "cannot create more", "reached your", "quota", "सीमा", "बाद में कोशिश", "बाद में फिर", "लिमिट")
        val REFUSE = listOf("can't create", "cannot create", "can't make", "can't generate", "i can't help", "not able to create", "unable to",
            "नहीं बना सकत", "मदद नहीं कर सकत")
        val READY = listOf("video is ready", "here's your video", "here is your video", "your video", "वीडियो तैयार", "आपका वीडियो")
        val VID_WORDS = listOf("video", "play", "वीडियो", "चलाएं", "चलाएँ")
        val VID_CLS = listOf("video", "surfaceview", "textureview", "playerview", "styledplayer")
        const val LOOK_VIDEO = "the newly generated VIDEO in the chat (a big video player / thumbnail with a play button)"
        const val LOOK_DOWNLOAD = "the DOWNLOAD button / icon for the video (a downward arrow, or 'Download' / 'Save' in a menu)"

        /** pure: chat में बड़े वीडियो (चौड़ाई ≥ 40%, ऊँचाई ≥ 15%) - player / "video" नाम वाली बड़ी चीज़ */
        fun videos(o: ScreenObservation): List<UiElement> = o.elements.filter { e ->
            !e.editable && e.bounds.width >= o.width * 0.4 && e.bounds.height >= o.height * 0.15 &&
                (VID_CLS.any { e.cls.lowercase().contains(it) } || VID_WORDS.any { e.desc.lowercase().contains(it) })
        }

        /** pure: नाम से वीडियो न मिले पर "Your video is ready" लिखा हो - लिखने वाले खाने के ऊपर सबसे बड़ी चीज़ */
        fun guessVideo(o: ScreenObservation): UiElement? {
            val top = ImageGenFlow.input(o)?.bounds?.top ?: o.height
            return o.elements.filter { !it.editable && it.bounds.bottom <= top && it.bounds.width >= o.width * 0.4 && it.bounds.height >= o.height * 0.15 && it.text.length < 40 }
                .maxByOrNull { it.bounds.width * it.bounds.height }
        }

        fun isReady(reply: String): Boolean { val t = reply.lowercase(); return READY.any { t.contains(it) } }

        fun busy(o: ScreenObservation): Boolean = ImageGenFlow.busy(o) || o.elements.any { e -> VIDEO_BUSY.any { e.label.contains(it) } }

        /** pure: जवाब में limit की बात (Gemini: "You've reached your limit… try again later") */
        fun isLimit(reply: String): Boolean { val t = reply.lowercase(); return t.isNotBlank() && LIMIT.any { t.contains(it) } }

        fun isRefusal(reply: String): Boolean { val t = reply.lowercase(); return t.isNotBlank() && REFUSE.any { t.contains(it) } }

        /** वीडियो का download बटन - "Download video" या आम Download/Save */
        fun downloadButton(o: ScreenObservation): UiElement? = o.elements
            .firstOrNull { e -> e.enabled && !e.editable && e.label.length <= 40 && (e.label.contains("download video") || e.label.contains("वीडियो डाउनलोड")) }
            ?: ImageGenFlow.downloadButton(o)
    }

    private val how = mutableListOf<String>()

    /** एक भाग: [prompt] [app] में ([fresh] = नई chat, सिर्फ़ पहले भाग में) */
    suspend fun makeClip(app: ChatTarget, prompt: String, fresh: Boolean): VideoClipResult {
        how.clear()
        val start = host.now()
        val before = d.capture()?.takeIf { it.pkg == app.pkg }
        val oldCount = before?.let { videos(it).size } ?: 0
        ImageGenFlow(d, host).typeAndSend(app, prompt, fresh)?.let { f ->
            return VideoClipResult(false, blocker = f.blocker, reply = f.appReply, error = f.error.ifBlank { "${app.hi} में prompt नहीं भेज पाया" })
        }
        // fresh chat = पुराने वीडियो गिनती में नहीं
        val baseCount = if (fresh) 0 else oldCount
        var seenBusy = false
        var lastReply = ""; var sameReply = 0
        var lastLook = 0L
        while (host.now() - start < maxWaitMs) {
            d.pause(pollMs)
            val o = d.capture() ?: continue
            if (o.pkg != app.pkg && o.pkg.isNotBlank()) {
                // कोई और ऐप ऊपर आ गया (notification / call खत्म) - वापस Gemini
                how += "reopen"; host.openApp(app.pkg); continue
            }
            BlockerDetector.detect(o)?.let { return VideoClipResult(false, blocker = it, error = "${app.hi} में ${it.hi} - पहले आप खुद पूरा कीजिए", how = how.joinToString()) }
            if (busy(o)) { seenBusy = true; continue }
            val vids = videos(o)
            if (vids.size > baseCount) {
                val v = vids.maxByOrNull { it.bounds.bottom } ?: vids.last()
                d.pause(1_500)          // पूरा लोड हो जाए
                return saved(app, v.bounds.centerX, v.bounds.centerY, start)
            }
            val reply = ImageGenFlow.answerText(o, prompt.take(60))
            if (reply.isNotBlank() && !ImageGenFlow.placeholder(reply)) {
                if (isLimit(reply)) return VideoClipResult(false, limit = true, reply = reply.take(300), error = "${app.hi} की वीडियो limit अभी खत्म है", how = how.joinToString())
                if (isReady(reply)) guessVideo(o)?.let { v -> how += "video:ready"; d.pause(1_500); return saved(app, v.bounds.centerX, v.bounds.centerY, start) }
                if (reply == lastReply) sameReply++ else { lastReply = reply; sameReply = 0 }
                // वीडियो नहीं, बस लिखा जवाब, और कुछ नहीं बन रहा = मना किया / सवाल पूछा
                if (!seenBusy && sameReply >= 6 && host.now() - start > 45_000) {
                    return VideoClipResult(false, reply = reply.take(300), error = if (isRefusal(reply)) "${app.hi} ने यह वीडियो बनाने से मना किया" else "${app.hi} ने वीडियो की जगह जवाब लिखा", how = how.joinToString())
                }
            }
            // नाम से न दिखे = स्क्रीन देखकर (हर 30 सेकंड, 40 सेकंड बाद से)
            if (host.canLook() && host.now() - start > 40_000 && host.now() - lastLook > 30_000) {
                lastLook = host.now()
                host.look(LOOK_VIDEO)?.let { (x, y) -> how += "video:look"; return saved(app, x, y, start) }
            }
        }
        return VideoClipResult(false, reply = lastReply.take(300), error = "${(maxWaitMs / 60_000)} मिनट में ${app.hi} में वीडियो नहीं बना", how = how.joinToString())
    }

    /** वीडियो सेव करो + chat पर वापस */
    private suspend fun saved(app: ChatTarget, x: Int, y: Int, start: Long): VideoClipResult {
        val file = download(x, y, start)
        backToChat(app)
        return if (file != null) VideoClipResult(true, file = file, how = how.joinToString())
        else VideoClipResult(false, error = "वीडियो बन गया पर डाउनलोड नहीं कर पाया", how = how.joinToString())
    }

    /** पहले chat में ही Download; न हो तो वीडियो खोलो -> Download / ⋮ / long-press / स्क्रीन देखकर */
    private suspend fun download(x: Int, y: Int, start: Long): String? {
        var opened = false; var menu = false; var longPressed = false; var looked = false
        for (attempt in 0 until 8) {
            val o = d.capture() ?: return null
            val btn = downloadButton(o)
            if (btn != null) {
                if (d.click(o, btn) != DriverResult.OK) d.tapAt(btn.bounds.centerX, btn.bounds.centerY)
                how += "download:label"
                return confirm(start)
            }
            when {
                !opened -> { opened = true; d.tapAt(x, y); d.pause(1_500) }
                !menu && ImageGenFlow.moreButton(o) != null -> { menu = true; ImageGenFlow.moreButton(o)?.let { d.click(o, it) }; d.pause(900) }
                !longPressed -> { longPressed = true; host.longPress(x, y); d.pause(1_000) }
                !looked && host.canLook() -> {
                    looked = true
                    val pt = host.look(LOOK_DOWNLOAD) ?: return null
                    d.tapAt(pt.first, pt.second); how += "download:look"
                    return confirm(start)
                }
                else -> return null
            }
        }
        return null
    }

    /** इजाज़त (Allow) दबाओ, फिर फ़ोन में वीडियो आने तक (बड़ी फ़ाइल - 40 सेकंड तक) */
    private suspend fun confirm(start: Long): String {
        for (i in 0 until 40) {
            d.pause(1_000)
            val p = d.capture()
            p?.elements?.firstOrNull { e -> e.clickable && ImageGenFlow.ALLOW.any { e.label == it || e.label.startsWith(it) } }?.let { d.click(p, it) }
            host.newestVideoSince(start)?.let { return it }
        }
        return host.newestVideoSince(start) ?: ImageGenFlow.UNVERIFIED
    }

    /** वीडियो पूरी स्क्रीन / menu खुला हो तो back - जब तक लिखने वाला खाना न दिखे (3 बार तक) */
    private suspend fun backToChat(app: ChatTarget) {
        for (i in 0 until 3) {
            val o = d.capture() ?: return
            if (o.pkg == app.pkg && ImageGenFlow.input(o) != null) return
            if (o.pkg != app.pkg) { host.openApp(app.pkg); d.pause(900); continue }
            d.back(); d.pause(900)
        }
    }
}
