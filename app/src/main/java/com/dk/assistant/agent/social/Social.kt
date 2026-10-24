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
    WHATSAPP("com.whatsapp", "WhatsApp");

    companion object {
        fun parse(s: String?): Platform? = when (s.orEmpty().trim().lowercase()) {
            "instagram", "insta", "ig", "इंस्टाग्राम", "इंस्टा" -> INSTAGRAM
            "facebook", "fb", "फेसबुक", "फ़ेसबुक" -> FACEBOOK
            "whatsapp", "wa", "व्हाट्सएप", "व्हाट्सऐप" -> WHATSAPP
            else -> null
        }
    }
}

enum class SocialAction(val needsMedia: Boolean, val irreversible: Boolean) {
    POST_PHOTO(true, true), POST_VIDEO(true, true), POST_REEL(true, true), POST_STORY(true, true), POST_TEXT(false, true),
    // WhatsApp
    READ_UNREAD(false, false), SEND_MESSAGE(false, true), READ_CHAT(false, false), SEARCH_CHAT(false, false),
    MUTE(false, false), UNMUTE(false, false), MARK_READ(false, false);

    companion object {
        fun parse(s: String?): SocialAction? = s.orEmpty().trim().uppercase().replace(' ', '_').replace('-', '_').let { k ->
            entries.firstOrNull { it.name == k } ?: when (k) {
                "PHOTO", "POST", "IMAGE", "POST_IMAGE" -> POST_PHOTO
                "VIDEO" -> POST_VIDEO
                "REEL", "REELS" -> POST_REEL
                "STORY", "STORIES" -> POST_STORY
                "TEXT", "STATUS_TEXT" -> POST_TEXT
                "SEND", "MESSAGE" -> SEND_MESSAGE
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
    data class ShareMedia(val pkg: String, val mime: String, val uri: String, val targetHints: List<String>, val text: String = "") : OpenSpec()
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
)

object SocialRequestParser {
    const val MAX_CAPTION = 2200
    const val MAX_MESSAGE = 4000

    /** Ok(request) या Err(यूज़र/मॉडल को बताने लायक कारण) */
    sealed class Parsed { data class Ok(val r: SocialRequest) : Parsed(); data class Err(val why: String) : Parsed() }

    fun parse(a: JSONObject, forcePlatform: Platform? = null): Parsed {
        val p = forcePlatform ?: Platform.parse(a.optString("platform")) ?: return Parsed.Err("platform must be instagram, facebook or whatsapp")
        val act = SocialAction.parse(a.optString("action")) ?: return Parsed.Err("unknown action")
        val wa = act in WHATSAPP_ACTIONS
        if (p == Platform.WHATSAPP && !wa) return Parsed.Err("WhatsApp supports: read_unread, send_message, read_chat, search_chat, mute, unmute, mark_read")
        if (p != Platform.WHATSAPP && wa) return Parsed.Err("this action is only for WhatsApp")
        if (p == Platform.INSTAGRAM && act == SocialAction.POST_TEXT) return Parsed.Err("Instagram needs a photo or video")
        if (p == Platform.FACEBOOK && act == SocialAction.POST_REEL) return Parsed.Err("Facebook reels are not supported yet - use post_video")
        val mode = if (a.optString("mode").trim().equals("draft", true)) TaskMode.DRAFT else TaskMode.PUBLISH
        val caption = a.optString("caption").trim()
        val message = a.optString("message").trim()
        val contact = a.optString("contact").trim()
        val query = a.optString("query").trim()
        if (caption.length > MAX_CAPTION) return Parsed.Err("caption is too long (max $MAX_CAPTION)")
        if (message.length > MAX_MESSAGE) return Parsed.Err("message is too long")
        when (act) {
            SocialAction.POST_TEXT -> if (caption.isEmpty()) return Parsed.Err("post_text needs caption (the text to post)")
            SocialAction.SEND_MESSAGE -> if (contact.isEmpty() || message.isEmpty()) return Parsed.Err("send_message needs contact and message")
            SocialAction.READ_CHAT, SocialAction.MUTE, SocialAction.UNMUTE, SocialAction.MARK_READ ->
                if (contact.isEmpty()) return Parsed.Err("${act.name.lowercase()} needs contact")
            SocialAction.SEARCH_CHAT -> if (query.isEmpty() && contact.isEmpty()) return Parsed.Err("search_chat needs query")
            else -> {}
        }
        return Parsed.Ok(SocialRequest(p, act, mode, caption, a.optString("media").trim(), contact, message, query.ifEmpty { contact }))
    }

    val WHATSAPP_ACTIONS = setOf(SocialAction.READ_UNREAD, SocialAction.SEND_MESSAGE, SocialAction.READ_CHAT, SocialAction.SEARCH_CHAT,
        SocialAction.MUTE, SocialAction.UNMUTE, SocialAction.MARK_READ)
}

/** सिर्फ़ वही media जो यूज़र ने खुद DK को share की (कोई और फ़ाइल नहीं) */
data class SharedItem(val uri: String, val mime: String, val at: Long)

object MediaPolicy {
    fun resolve(requested: String, allowed: List<SharedItem>, wantVideo: Boolean?): SharedItem? {
        val list = allowed.sortedByDescending { it.at }
        val pool = when (wantVideo) { true -> list.filter { it.mime.startsWith("video/") }; false -> list.filter { it.mime.startsWith("image/") }; null -> list }
        val r = requested.trim()
        if (r.isEmpty() || r.equals("latest_shared", true) || r.equals("latest", true)) return pool.firstOrNull()
        return pool.firstOrNull { it.uri == r }
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

    // WhatsApp
    val WA_INPUT = TargetSpec(listOf("message", "type a message", "संदेश"), listOf("entry"), editable = true, name = "message box")
    val WA_SEND = TargetSpec(listOf("send", "भेजें"), listOf("send", "conversation_send"), exact = true, name = "Send")
    val WA_SEARCH = TargetSpec(listOf("search", "खोजें", "ask meta ai or search"), listOf("menuitem_search", "my_search_bar", "search_bar"), name = "Search")
    val WA_SEARCH_INPUT = TargetSpec(listOf("search", "खोजें"), listOf("search_input", "search_src_text"), editable = true, name = "search box")
    val WA_MORE = TargetSpec(listOf("more options", "और विकल्प", "अधिक विकल्प"), listOf("menuitem_overflow"), name = "More options")
    val WA_MUTE = TargetSpec(listOf("mute notifications", "mute", "सूचनाएँ म्यूट करें", "म्यूट करें"), emptyList(), exact = true, name = "Mute")
    val WA_UNMUTE = TargetSpec(listOf("unmute notifications", "unmute", "अनम्यूट करें", "अनम्यूट"), emptyList(), exact = true, name = "Unmute")
    val WA_DIALOG_OK = TargetSpec(listOf("ok", "mute", "ठीक है", "म्यूट करें"), listOf("button1", "ok"), exact = true, clickableOnly = true, name = "OK")
}
