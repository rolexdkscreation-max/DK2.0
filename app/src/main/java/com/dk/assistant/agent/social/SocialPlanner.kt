package com.dk.assistant.agent.social

import java.net.URLEncoder

/** request -> कदमों की सूची (pure). media / फ़ोन नंबर पहले से तय (Android हिस्सा देता है) */
object SocialPlanner {

    fun plan(id: String, r: SocialRequest, media: SharedItem?, phoneDigits: String?, contactName: String = r.contact): SocialTask {
        val S = Selectors
        val cap = r.caption
        return when (r.platform) {
            Platform.INSTAGRAM -> {
                val m = media!!
                when (r.action) {
                    SocialAction.POST_STORY -> SocialTask(id, r.platform, r.action, r.mode,
                        OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, S.IG_STORY_HINT),
                        listOf(PlanStep.Final(S.IG_STORY_SHARE, S.IG_STORY_DONE, S.IG_STORY_SHARE)),
                        "Instagram स्टोरी में यह ${kind(m)} डाल दूँ?")
                    else -> {
                        val hint = if (r.action == SocialAction.POST_REEL) S.IG_REEL_HINT else S.IG_FEED_HINT
                        val steps = buildList {
                            add(PlanStep.AdvanceUntil(S.IG_NEXT, S.IG_CAPTION, max = 3))
                            if (cap.isNotEmpty()) add(PlanStep.Type(S.IG_CAPTION, cap))
                            add(PlanStep.Final(S.IG_SHARE, S.IG_DONE, S.IG_CAPTION))
                        }
                        SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, hint), steps,
                            "Instagram पर यह ${kind(m)}" + captionPart(cap) + " पोस्ट कर दूँ?")
                    }
                }
            }
            Platform.FACEBOOK -> when (r.action) {
                SocialAction.POST_TEXT -> SocialTask(id, r.platform, r.action, r.mode,
                    OpenSpec.ShareText(r.platform.pkg, cap, S.FB_FEED_HINT),
                    listOf(PlanStep.Type(S.FB_TEXT, cap, optional = true), PlanStep.Final(S.FB_POST, S.FB_DONE, S.FB_TEXT)),
                    "Facebook पर यह लिखकर पोस्ट कर दूँ: \"${short(cap)}\"?")
                SocialAction.POST_STORY -> {
                    val m = media!!
                    SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, S.FB_STORY_HINT),
                        listOf(PlanStep.Final(S.FB_STORY_SHARE, S.FB_DONE, S.FB_STORY_SHARE)), "Facebook स्टोरी में यह ${kind(m)} डाल दूँ?")
                }
                else -> {
                    val m = media!!
                    val steps = buildList {
                        add(PlanStep.Tap(S.FB_NEXT, optional = true))
                        if (cap.isNotEmpty()) add(PlanStep.Type(S.FB_TEXT, cap, optional = true))
                        add(PlanStep.Final(S.FB_POST, S.FB_DONE, S.FB_POST))
                    }
                    SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, S.FB_FEED_HINT, cap), steps,
                        "Facebook पर यह ${kind(m)}" + captionPart(cap) + " पोस्ट कर दूँ?")
                }
            }
            Platform.WHATSAPP -> {
                val chat = phoneDigits?.let { OpenSpec.Url("https://wa.me/$it", r.platform.pkg) }
                when (r.action) {
                    SocialAction.READ_UNREAD -> SocialTask(id, r.platform, r.action, r.mode, OpenSpec.None, emptyList(), "")
                    SocialAction.SEND_MESSAGE -> SocialTask(id, r.platform, r.action, r.mode,
                        OpenSpec.Url("https://wa.me/$phoneDigits?text=" + enc(r.message), r.platform.pkg),
                        listOf(PlanStep.Final(S.WA_SEND, listOf(r.message.trim().take(24)), S.WA_SEND, requireBoth = true)),
                        "$contactName को WhatsApp पर यह भेज दूँ: \"${short(r.message)}\"?")
                    SocialAction.READ_CHAT -> SocialTask(id, r.platform, r.action, r.mode, chat!!, listOf(PlanStep.Read(25)), "")
                    SocialAction.MARK_READ -> SocialTask(id, r.platform, r.action, r.mode, chat!!, listOf(PlanStep.Read(1)), "")
                    SocialAction.SEARCH_CHAT -> SocialTask(id, r.platform, r.action, r.mode, OpenSpec.Launch(r.platform.pkg),
                        listOf(PlanStep.Tap(S.WA_SEARCH), PlanStep.Type(S.WA_SEARCH_INPUT, r.query), PlanStep.Read(20)), "")
                    SocialAction.MUTE -> SocialTask(id, r.platform, r.action, r.mode, chat!!,
                        listOf(PlanStep.Tap(S.WA_MORE), PlanStep.Tap(S.WA_MUTE), PlanStep.Tap(S.WA_DIALOG_OK, optional = true)), "")
                    SocialAction.UNMUTE -> SocialTask(id, r.platform, r.action, r.mode, chat!!,
                        listOf(PlanStep.Tap(S.WA_MORE), PlanStep.Tap(S.WA_UNMUTE)), "")
                    else -> error("not a WhatsApp action")
                }
            }
        }
    }

    /** WhatsApp के लिए नंबर: सिर्फ़ अंक, 10 अंक = भारत (+91) मानो */
    fun waDigits(raw: String, defaultCountry: String = "91"): String? {
        var d = raw.filter { it.isDigit() }
        if (raw.trim().startsWith("00")) d = d.removePrefix("00")
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = defaultCountry + d
        return d.takeIf { it.length in 11..15 }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun kind(m: SharedItem) = if (m.mime.startsWith("video/")) "वीडियो" else "फोटो"
    private fun short(s: String) = if (s.length > 80) s.take(77) + "…" else s
    private fun captionPart(c: String) = if (c.isEmpty()) "" else " (कैप्शन: \"${short(c)}\")"
}
