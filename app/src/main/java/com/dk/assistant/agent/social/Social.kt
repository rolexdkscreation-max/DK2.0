package com.dk.assistant.agent.social

import com.dk.assistant.agent.core.TargetSpec
import org.json.JSONObject

/**
 * DK AI Part 12: Instagram / Facebook / WhatsApp - काम की परिभाषा (pure, JVM test).
 * पोस्ट करना / संदेश भेजना वापस नहीं होता -> हमेशा यूज़र का साफ़ "हाँ" पहले (SocialTaskRunner).
 */
enum class Platform(val pkg: String, val label: String) {
    INSTAGRAM("com.instagram.android", "Instagram"),
    FACEBOOK("com.facebook.katana", "Facebook"),
    WHATSAPP("com.whatsapp", "WhatsApp"),
    /** 16 नवंबर 2026 (मालिक: "YouTube Shorts भी जोड़ो") - सिर्फ़ वीडियो (Shorts) */
    YOUTUBE("com.google.android.youtube", "YouTube");

    companion object {
        fun parse(s: String?): Platform? = when (s.orEmpty().trim().lowercase()) {
            "instagram", "insta", "ig", "इंस्टाग्राम", "इंस्टा" -> INSTAGRAM
            "facebook", "fb", "फेसबुक", "फ़ेसबुक" -> FACEBOOK
            "whatsapp", "wa", "व्हाट्सएप", "व्हाट्सऐप" -> WHATSAPP
            "youtube", "yt", "youtube shorts", "youtube_shorts", "shorts", "यूट्यूब", "यूट्यूब शॉर्ट्स" -> YOUTUBE
            else -> null
        }
    }
}

enum class SocialAction(val needsMedia: Boolean, val irreversible: Boolean) {
    POST_PHOTO(true, true), POST_VIDEO(true, true), POST_REEL(true, true), POST_STORY(true, true), POST_TEXT(false, true),
    // WhatsApp
    READ_UNREAD(false, false), SEND_MESSAGE(false, true), READ_CHAT(false, false), SEARCH_CHAT(false, false),
    MUTE(false, false), UNMUTE(false, false), MARK_READ(false, false),
    /** नवंबर 2026: फ़ोन में से फ़ाइल (फोटो / PDF / वीडियो / DK की बनाई वेबसाइट या गेम) ढूँढकर WhatsApp पर भेजो */
    SEND_FILE(false, true);

    companion object {
        fun parse(s: String?): SocialAction? = s.orEmpty().trim().uppercase().replace(' ', '_').replace('-', '_').let { k ->
            entries.firstOrNull { it.name == k } ?: when (k) {
                "PHOTO", "POST", "IMAGE", "POST_IMAGE" -> POST_PHOTO
                "VIDEO" -> POST_VIDEO
                "REEL", "REELS" -> POST_REEL
                "STORY", "STORIES", "STATUS", "POST_STATUS", "WHATSAPP_STATUS", "STATUS_UPDATE", "PUT_STATUS" -> POST_STORY
                "TEXT", "STATUS_TEXT" -> POST_TEXT
                "SEND", "MESSAGE" -> SEND_MESSAGE
                "FILE", "SEND_DOCUMENT", "DOCUMENT", "SEND_PHOTO", "SEND_MEDIA", "SHARE_FILE" -> SEND_FILE
                "UNREAD", "READ" -> READ_UNREAD
                else -> null
            }
        }
    }
}

enum class TaskMode { PUBLISH, DRAFT }

enum class TaskStatus { RUNNING, WAITING_CONFIRMATION, WAITING_USER, COMPLETED, SUBMITTED_UNVERIFIED, DRAFT_READY, FAILED, CANCELLED;
    val finished get() = this == COMPLETED || this == SUBMITTED_UNVERIFIED || this == DRAFT_READY || this == FAILED || this == CANCELLED
}

/** app खोलने का तरीका (Android वाला हिस्सा असल में करता है) */
sealed class OpenSpec {
    /** jid = WhatsApp में सीधे उसी की चैट ("91…@s.whatsapp.net") - चुनने वाली सूची नहीं */
    data class ShareMedia(val pkg: String, val mime: String, val uri: String, val targetHints: List<String>, val text: String = "", val jid: String = "",
                          /** 25 नवंबर 2026: एक ही पोस्ट में कई फोटो/वीडियो (Instagram carousel / Facebook) - ACTION_SEND_MULTIPLE. खाली = एक ([uri]) */
                          val uris: List<String> = emptyList()) : OpenSpec()
    data class ShareText(val pkg: String, val text: String, val targetHints: List<String> = emptyList()) : OpenSpec()
    data class Url(val url: String, val pkg: String) : OpenSpec()
    data class Launch(val pkg: String) : OpenSpec()
    /** स्क्रीन नहीं - जैसे WhatsApp की unread सूचनाएँ */
    object None : OpenSpec()
}

sealed class PlanStep {
    abstract val name: String
    abstract val optional: Boolean
    data class Tap(val spec: TargetSpec, override val optional: Boolean = false, override val name: String = spec.name) : PlanStep()
    data class Type(val spec: TargetSpec, val text: String, override val optional: Boolean = false, override val name: String = spec.name) : PlanStep()
    /** until दिखने तक tap दबाते रहो (जैसे Instagram में कई "Next") */
    data class AdvanceUntil(val tap: TargetSpec, val until: TargetSpec, val max: Int = 3, override val optional: Boolean = false, override val name: String = "advance") : PlanStep()
    /** वापस न होने वाला आख़िरी टैप - पहले "हाँ" */
    data class Final(val spec: TargetSpec, val successWords: List<String>, val goneSpec: TargetSpec, val requireBoth: Boolean = false,
                     override val optional: Boolean = false, override val name: String = spec.name) : PlanStep()
    data class Read(val maxLines: Int = 20, override val optional: Boolean = false, override val name: String = "read") : PlanStep()
    /**
     * 16 नवंबर 2026 (गाना / टैग): हो जाए तो अच्छा - न हो तो पोस्ट नहीं रुकती, पर आख़िरी सवाल और नतीजे में साफ़ बताया जाता है (warn).
     * गड़बड़ हो तो back दबाकर anchor (जैसे Share बटन) वाली स्क्रीन पर लौटो. okNote में {pick} = जो नतीजा चुना गया.
     */
    data class Soft(val steps: List<PlanStep>, val warn: String, val anchor: TargetSpec?, val okNote: String = "",
                    override val optional: Boolean = true, override val name: String = "soft") : PlanStep()
    /** स्क्रीन के हिस्से पर टैप (0..1) - जैसे "फोटो पर टैप करके टैग करें" */
    data class TapAt(val xFrac: Float, val yFrac: Float, override val optional: Boolean = false, override val name: String = "tap") : PlanStep()
    /** खोज के नतीजों में से वह जिसमें query लिखा हो (लिखने का खाना नहीं) */
    data class PickResult(val query: String, override val optional: Boolean = false, override val name: String = query) : PlanStep()
}

/** खोज के नतीजों में से सही वाला (pure) */
object ResultPick {
    private val CHROME = listOf("search", "खोज", "cancel", "रद्द", "clear", "done", "हो गया", "back", "पीछे", "see all", "सभी देखें")

    fun choose(obs: com.dk.assistant.agent.core.ScreenObservation, query: String): com.dk.assistant.agent.core.UiElement? {
        val q = query.trim().lowercase()
        if (q.length < 2) return null
        val words = q.split(Regex("[^\\p{L}\\p{M}\\p{N}_.]+")).filter { it.length >= 2 }
        val below = obs.elements.filter { it.editable }.minByOrNull { it.bounds.top }?.bounds?.bottom ?: 0
        val hits = obs.elements.filter { e ->
            e.enabled && !e.editable && e.bounds.width > 0 && e.bounds.height > 0 && e.bounds.centerY > below &&
                CHROME.none { c -> e.label.startsWith(c) } &&
                (e.label.contains(q) || (words.isNotEmpty() && words.all { e.label.contains(it) }))
        }
        return (hits.filter { it.clickable }.ifEmpty { hits }).minByOrNull { it.bounds.top }
    }
}

data class SocialTask(
    val id: String,
    val platform: Platform,
    val action: SocialAction,
    val mode: TaskMode,
    val open: OpenSpec,
    val steps: List<PlanStep>,
    /** "हाँ" माँगते समय यूज़र को क्या बताना है */
    val confirmText: String,
)

data class TaskReport(
    val taskId: String,
    val platform: Platform,
    val action: SocialAction,
    val status: TaskStatus,
    val message: String,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val readText: String = "",
) {
    /** मॉडल के लिए (tool का जवाब / SYSTEM NOTE) */
    fun toJson(): JSONObject = JSONObject().put("task_id", taskId).put("platform", platform.name.lowercase())
        .put("action", action.name.lowercase()).put("status", status.name.lowercase()).put("message", message)
        .put("step", step).put("total_steps", totalSteps).apply { if (readText.isNotEmpty()) put("content", readText) }
}

data class SocialRequest(
    val platform: Platform,
    val action: SocialAction,
    val mode: TaskMode,
    val caption: String,
    val media: String,
    val contact: String,
    val message: String,
    val query: String,
    /** SEND_FILE: कौन सी फ़ाइल (यूज़र के शब्द - "आधार कार्ड वाली PDF", "latest_photo") */
    val file: String = "",
    /** 16 नवंबर 2026 (सोशल पोस्ट स्पेशलिस्ट): गैलरी में कौन सी फोटो/वीडियो - अंग्रेज़ी वर्णन ("sunset at the beach") */
    val mediaQuery: String = "",
    /** सिर्फ़ पिछले इतने दिन की गैलरी (0 = सब) */
    val mediaDays: Int = 0,
    /** फोटो/वीडियो देखकर कैप्शन + हैशटैग खुद */
    val autoCaption: Boolean = false,
    val captionHint: String = "",
    val captionLanguage: String = "",
    /** "" = गाना नहीं (सिर्फ़ बोलने पर), "auto" = मूड से, या गाने का नाम */
    val song: String = "",
    /** किस-किस को टैग करना है (नाम / username) */
    val tags: List<String> = emptyList(),
    /** आख़िरी "हाँ" वाले सवाल में सुनाने के लिए: क्या चुना, क्या लिखा (SocialAgent भरता है) */
    val confirmExtra: String = "",
) {
    /** कोई नई (स्पेशलिस्ट) चीज़ माँगी गई */
    val smart: Boolean get() = mediaQuery.isNotEmpty() || autoCaption || song.isNotEmpty() || tags.isNotEmpty() || platform == Platform.YOUTUBE
}

object SocialRequestParser {
    const val MAX_CAPTION = 2200
    const val MAX_MESSAGE = 4000

    /** Ok(request) या Err(यूज़र/मॉडल को बताने लायक कारण) */
    sealed class Parsed { data class Ok(val r: SocialRequest) : Parsed(); data class Err(val why: String) : Parsed() }

    fun parse(a: JSONObject, forcePlatform: Platform? = null): Parsed {
        val p = forcePlatform ?: Platform.parse(a.optString("platform")) ?: return Parsed.Err("platform must be instagram, facebook, youtube or whatsapp")
        val act = SocialAction.parse(a.optString("action")) ?: return Parsed.Err("unknown action")
        val wa = act in WHATSAPP_ACTIONS
        // 25 नवंबर 2026 (मालिक): WhatsApp स्टेटस = post_story (DK को share की गई फोटो/वीडियो)
        if (p == Platform.WHATSAPP && !wa && act != SocialAction.POST_STORY)
            return Parsed.Err("WhatsApp supports: read_unread, send_message, send_file, read_chat, search_chat, mute, unmute, mark_read, post_status (WhatsApp Status)")
        if (p != Platform.WHATSAPP && wa) return Parsed.Err("this action is only for WhatsApp")
        if (p == Platform.INSTAGRAM && act == SocialAction.POST_TEXT) return Parsed.Err("Instagram needs a photo or video")
        if (p == Platform.FACEBOOK && act == SocialAction.POST_REEL) return Parsed.Err("Facebook reels are not supported yet - use post_video")
        if (p == Platform.YOUTUBE && act != SocialAction.POST_REEL && act != SocialAction.POST_VIDEO) return Parsed.Err("YouTube Shorts needs a video - use post_reel (or post_video)")
        val mode = if (a.optString("mode").trim().equals("draft", true)) TaskMode.DRAFT else TaskMode.PUBLISH
        val caption = a.optString("caption").trim().ifEmpty { if (p == Platform.WHATSAPP && act == SocialAction.POST_STORY) a.optString("message").trim() else "" }
        val message = a.optString("message").trim()
        val contact = a.optString("contact").trim()
        val query = a.optString("query").trim()
        if (caption.length > MAX_CAPTION) return Parsed.Err("caption is too long (max $MAX_CAPTION)")
        if (message.length > MAX_MESSAGE) return Parsed.Err("message is too long")
        when (act) {
            SocialAction.POST_TEXT -> if (caption.isEmpty()) return Parsed.Err("post_text needs caption (the text to post)")
            SocialAction.SEND_MESSAGE -> if (contact.isEmpty() || message.isEmpty()) return Parsed.Err("send_message needs contact and message")
            SocialAction.SEND_FILE -> if (contact.isEmpty() || a.optString("file").isBlank()) return Parsed.Err("send_file needs contact and file (which file, in the user's words, or latest_photo)")
            SocialAction.READ_CHAT, SocialAction.MUTE, SocialAction.UNMUTE, SocialAction.MARK_READ ->
                if (contact.isEmpty()) return Parsed.Err("${act.name.lowercase()} needs contact")
            SocialAction.SEARCH_CHAT -> if (query.isEmpty() && contact.isEmpty()) return Parsed.Err("search_chat needs query")
            else -> {}
        }
        val tags = a.optJSONArray("tags")?.let { arr -> SocialSmart.parseTags((0 until arr.length()).joinToString(",") { arr.optString(it) }) }
            ?: SocialSmart.parseTags(a.optString("tags"))
        if (p == Platform.WHATSAPP && tags.isNotEmpty()) return Parsed.Err("tags are only for posts")
        return Parsed.Ok(SocialRequest(p, act, mode, caption, a.optString("media").trim(), contact, message, query.ifEmpty { contact }, a.optString("file").trim(),
            mediaQuery = a.optString("media_query").trim().take(200), mediaDays = a.optInt("media_days", 0).coerceIn(0, 3650),
            autoCaption = a.optBoolean("auto_caption", false), captionHint = a.optString("caption_hint").trim().take(300),
            captionLanguage = a.optString("caption_language").trim().take(20), song = SocialSmart.normSong(a.optString("song")), tags = tags))
    }

    val WHATSAPP_ACTIONS = setOf(SocialAction.READ_UNREAD, SocialAction.SEND_MESSAGE, SocialAction.READ_CHAT, SocialAction.SEARCH_CHAT,
        SocialAction.MUTE, SocialAction.UNMUTE, SocialAction.MARK_READ, SocialAction.SEND_FILE)
}

/** सिर्फ़ वही media जो यूज़र ने खुद DK को share की (कोई और फ़ाइल नहीं). batch = एक साथ share की गई (गैलरी में कई चुनकर) - वही अंक */
data class SharedItem(val uri: String, val mime: String, val at: Long, val name: String = "", val batch: Long = 0)

object MediaPolicy {
    fun resolve(requested: String, allowed: List<SharedItem>, wantVideo: Boolean?): SharedItem? {
        val list = allowed.sortedByDescending { it.at }
        val pool = when (wantVideo) { true -> list.filter { it.mime.startsWith("video/") }; false -> list.filter { it.mime.startsWith("image/") }; null -> list }
        val r = requested.trim()
        if (r.isEmpty() || r.equals("latest_shared", true) || r.equals("latest", true)) return pool.firstOrNull()
        return pool.firstOrNull { it.uri == r }
    }

    /** 25 नवंबर 2026 (मालिक: "10 फोटो भेजूँ तो सब पता हो"): "all_shared" / "last_5" / "all" जैसा कई वाला माँगा? */
    fun isMany(requested: String): Boolean = many(requested.trim().lowercase()) != null

    private val ALL = setOf("all_shared", "all", "batch", "last_batch", "shared_all", "sab", "sabhi", "सब", "सभी")
    /** null = एक वाला; 0 = आख़िरी बार एक साथ भेजी सब; N = आख़िरी N */
    private fun many(r: String): Int? = when {
        r in ALL -> 0
        Regex("^(?:last|latest|recent)_(\\d{1,2})$").matchEntire(r) != null -> Regex("\\d+").find(r)!!.value.toInt().takeIf { it > 0 }
        else -> null
    }

    /**
     * कई फोटो/वीडियो - शेयर करने के क्रम में (पहली भेजी = पहली). अकेली वाली माँग = पहले जैसा [resolve] (0 या 1).
     * wantVideo: true = सिर्फ़ वीडियो, false = सिर्फ़ फोटो, null = दोनों.
     */
    fun resolveMany(requested: String, allowed: List<SharedItem>, wantVideo: Boolean?, max: Int = 20): List<SharedItem> {
        val n = many(requested.trim().lowercase()) ?: return listOfNotNull(resolve(requested, allowed, wantVideo))
        val list = allowed.sortedByDescending { it.at }
        val head = list.firstOrNull() ?: return emptyList()
        val picked = if (n == 0) {
            if (head.batch == 0L) listOf(head) else list.filter { it.batch == head.batch }
        } else list.take(n)
        val pool = when (wantVideo) { true -> picked.filter { it.mime.startsWith("video/") }; false -> picked.filter { it.mime.startsWith("image/") }; null -> picked }
        return pool.sortedBy { it.at }.take(max.coerceAtLeast(1))
    }

    /** "3 फोटो और 2 वीडियो" */
    fun countLabel(items: List<SharedItem>): String {
        val v = items.count { it.mime.startsWith("video/") }
        val p = items.size - v
        return listOfNotNull(if (p > 0) "$p फोटो" else null, if (v > 0) "$v वीडियो" else null).joinToString(" और ").ifEmpty { "कुछ नहीं" }
    }
}

/** स्क्रीन पर चीज़ें पहचानने के शब्द (अंग्रेज़ी + हिंदी UI) */
object Selectors {
    // Instagram
    val IG_NEXT = TargetSpec(listOf("next", "आगे", "अगला"), listOf("next_button_textview", "next_button_imageview", "next_button"), exact = true, name = "Next")
    val IG_CAPTION = TargetSpec(listOf("write a caption", "add a caption", "caption", "कैप्शन"), listOf("caption_text_view", "caption_input_text_view"), editable = true, name = "caption")
    val IG_SHARE = TargetSpec(listOf("share", "शेयर करें", "शेयर"), listOf("share_footer_button", "share_button"), exact = true, name = "Share")
    val IG_STORY_SHARE = TargetSpec(listOf("your story", "आपकी स्टोरी", "share to story", "स्टोरी"), listOf("share_story_button", "story_share_button"), name = "Your story")
    val IG_DONE = listOf("sharing", "shared", "posted", "your post", "post shared", "uploading", "आपकी पोस्ट", "शेयर हो", "पोस्ट हो गई", "शेयर किया जा रहा")
    val IG_STORY_DONE = listOf("sending", "sent", "added to your story", "posting", "स्टोरी में जोड़", "भेजा")
    val IG_FEED_HINT = listOf("feed", "post", "फ़ीड", "पोस्ट")
    val IG_STORY_HINT = listOf("stor", "स्टोरी")
    val IG_REEL_HINT = listOf("reel", "रील")

    // Facebook
    val FB_TEXT = TargetSpec(listOf("what's on your mind", "say something", "write something", "आपके मन में क्या", "कुछ लिखें", "कुछ कहें"), listOf("composer_text", "status_text"), editable = true, name = "post text")
    val FB_NEXT = TargetSpec(listOf("next", "आगे"), emptyList(), exact = true, name = "Next")
    val FB_POST = TargetSpec(listOf("post", "पोस्ट करें", "पोस्ट", "share now", "अभी शेयर करें"), listOf("composer_primary_action", "primary_named_button"), exact = true, name = "Post")
    val FB_STORY_SHARE = TargetSpec(listOf("share to story", "share now", "add to story", "स्टोरी में शेयर करें", "शेयर करें", "share"), emptyList(), exact = true, name = "Share to story")
    val FB_DONE = listOf("posting", "posted", "your post is", "your post", "uploading", "पोस्ट हो", "पोस्ट किया जा रहा")
    val FB_FEED_HINT = listOf("feed", "news feed", "post", "फ़ेसबुक", "facebook")
    val FB_STORY_HINT = listOf("stor", "स्टोरी")

    // गाना / टैग (16 नवंबर 2026) - न मिले तो Soft कदम चेतावनी देता है, काम नहीं रुकता
    val IG_ADD_MUSIC = TargetSpec(listOf("add music", "add audio", "music", "संगीत जोड़ें", "म्यूज़िक जोड़ें", "संगीत"), listOf("music_row", "add_music", "music_picker_button"), name = "Add music")
    val IG_REEL_AUDIO = TargetSpec(listOf("add audio", "audio", "music", "ऑडियो जोड़ें", "ऑडियो", "संगीत"), listOf("clips_action_bar_music_button", "music_button", "audio_button"), name = "Audio")
    val MUSIC_SEARCH = TargetSpec(listOf("search music", "search", "संगीत खोजें", "खोजें"), listOf("search_edit_text", "row_search_edit_text", "action_bar_search_edit_text", "search_box"), editable = true, name = "music search")
    val PICK_DONE = TargetSpec(listOf("done", "add", "use this sound", "हो गया", "जोड़ें", "इस साउंड का इस्तेमाल करें"), listOf("done_button", "select_button", "action_bar_button_action"), exact = true, name = "Done")
    val IG_TAG_PEOPLE = TargetSpec(listOf("tag people", "लोगों को टैग करें", "टैग करें"), listOf("tag_people", "people_tagging_button", "tag_people_row"), name = "Tag people")
    val PEOPLE_SEARCH = TargetSpec(listOf("search for a person", "search for friends", "search", "व्यक्ति खोजें", "दोस्त खोजें", "खोजें"), listOf("row_search_edit_text", "search_edit_text"), editable = true, name = "search")
    val FB_TAG = TargetSpec(listOf("tag people", "tag friends", "लोगों को टैग करें", "दोस्तों को टैग करें"), emptyList(), name = "Tag people")
    val FB_MUSIC = TargetSpec(listOf("add music", "music", "संगीत जोड़ें", "संगीत"), emptyList(), name = "Music")
    val YT_ADD_SOUND = TargetSpec(listOf("add sound", "sound", "साउंड जोड़ें", "ध्वनि जोड़ें", "साउंड"), listOf("shorts_camera_music_button", "music_button"), name = "Add sound")

    // YouTube Shorts (16 नवंबर 2026)
    val YT_NEXT = TargetSpec(listOf("next", "done", "आगे", "अगला", "हो गया"), listOf("shorts_trim_finish_trim_button", "shorts_camera_next_button", "next_button"), exact = true, name = "Next")
    val YT_TITLE = TargetSpec(listOf("caption your short", "create a title", "add a title", "title", "शॉर्ट का कैप्शन", "शीर्षक बनाएं", "शीर्षक जोड़ें", "शीर्षक", "कैप्शन"),
        listOf("title_edit", "title_edit_text", "shorts_title"), editable = true, name = "title")
    val YT_UPLOAD = TargetSpec(listOf("upload short", "upload", "शॉर्ट अपलोड करें", "अपलोड करें", "अपलोड"), listOf("upload_button", "shorts_upload_button"), exact = true, name = "Upload Short")
    val YT_DONE = listOf("uploading", "upload complete", "processing", "your short", "अपलोड हो रहा", "अपलोड हो गया", "अपलोड किया जा रहा", "प्रोसेस")
    val YT_HINT = listOf("short", "upload", "youtube")

    // WhatsApp
    val WA_INPUT = TargetSpec(listOf("message", "type a message", "संदेश"), listOf("entry"), editable = true, name = "message box")
    val WA_SEND = TargetSpec(listOf("send", "भेजें"), listOf("send", "conversation_send"), exact = true, name = "Send")
    val WA_SEARCH = TargetSpec(listOf("search", "खोजें", "ask meta ai or search"), listOf("menuitem_search", "my_search_bar", "search_bar"), name = "Search")
    val WA_SEARCH_INPUT = TargetSpec(listOf("search", "खोजें"), listOf("search_input", "search_src_text"), editable = true, name = "search box")
    val WA_MORE = TargetSpec(listOf("more options", "और विकल्प", "अधिक विकल्प"), listOf("menuitem_overflow"), name = "More options")
    val WA_MUTE = TargetSpec(listOf("mute notifications", "mute", "सूचनाएँ म्यूट करें", "म्यूट करें"), emptyList(), exact = true, name = "Mute")
    val WA_UNMUTE = TargetSpec(listOf("unmute notifications", "unmute", "अनम्यूट करें", "अनम्यूट"), emptyList(), exact = true, name = "Unmute")
    // WhatsApp स्टेटस (25 नवंबर 2026)
    val WA_MY_STATUS = TargetSpec(listOf("my status", "मेरा स्टेटस", "my status update"), emptyList(), exact = true, name = "My status")
    val WA_CAPTION = TargetSpec(listOf("add a caption", "add a caption…", "caption", "कैप्शन जोड़ें", "कैप्शन"), listOf("caption"), editable = true, name = "caption")
    val WA_STATUS_DONE = listOf("my status", "मेरा स्टेटस", "sending", "भेजा जा रहा")
    val WA_DIALOG_OK = TargetSpec(listOf("ok", "mute", "ठीक है", "म्यूट करें"), listOf("button1", "ok"), exact = true, clickableOnly = true, name = "OK")
}
