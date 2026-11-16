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
                        ask(r, "Instagram स्टोरी में यह ${kind(m)} डाल दूँ?"))
                    else -> {
                        val hint = if (r.action == SocialAction.POST_REEL) S.IG_REEL_HINT else S.IG_FEED_HINT
                        val steps = buildList {
                            add(PlanStep.AdvanceUntil(S.IG_NEXT, S.IG_CAPTION, max = 3))
                            if (cap.isNotEmpty()) add(PlanStep.Type(S.IG_CAPTION, cap))
                            add(PlanStep.Final(S.IG_SHARE, S.IG_DONE, S.IG_CAPTION))
                        }
                        SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, hint), steps,
                            ask(r, "Instagram पर यह ${kind(m)}" + captionPart(cap) + " पोस्ट कर दूँ?"))
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
                        listOf(PlanStep.Final(S.FB_STORY_SHARE, S.FB_DONE, S.FB_STORY_SHARE)), ask(r, "Facebook स्टोरी में यह ${kind(m)} डाल दूँ?"))
                }
                else -> {
                    val m = media!!
                    val steps = buildList {
                        add(PlanStep.Tap(S.FB_NEXT, optional = true))
                        if (cap.isNotEmpty()) add(PlanStep.Type(S.FB_TEXT, cap, optional = true))
                        add(PlanStep.Final(S.FB_POST, S.FB_DONE, S.FB_POST))
                    }
                    SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, S.FB_FEED_HINT, cap), steps,
                        ask(r, "Facebook पर यह ${kind(m)}" + captionPart(cap) + " पोस्ट कर दूँ?"))
                }
            }
            // 16 नवंबर 2026 (मालिक): YouTube Shorts - वीडियो share -> (Next) -> शीर्षक/कैप्शन -> "हाँ" -> Upload
            Platform.YOUTUBE -> {
                val m = media!!
                val steps = buildList {
                    add(PlanStep.AdvanceUntil(S.YT_NEXT, S.YT_TITLE, max = 4))
                    if (cap.isNotEmpty()) add(PlanStep.Type(S.YT_TITLE, cap))
                    add(PlanStep.Final(S.YT_UPLOAD, S.YT_DONE, S.YT_UPLOAD))
                }
                SocialTask(id, r.platform, r.action, r.mode, OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, S.YT_HINT), steps,
                    ask(r, "YouTube Shorts पर यह वीडियो" + captionPart(cap) + " अपलोड (पब्लिक) कर दूँ?"))
            }
            Platform.WHATSAPP -> {
                val chat = phoneDigits?.let { OpenSpec.Url("https://wa.me/$it", r.platform.pkg) }
                when (r.action) {
                    SocialAction.READ_UNREAD -> SocialTask(id, r.platform, r.action, r.mode, OpenSpec.None, emptyList(), "")
                    SocialAction.SEND_MESSAGE -> SocialTask(id, r.platform, r.action, r.mode,
                        OpenSpec.Url("https://wa.me/$phoneDigits?text=" + enc(r.message), r.platform.pkg),
                        listOf(PlanStep.Final(S.WA_SEND, listOf(r.message.trim().take(24)), S.WA_SEND, requireBoth = true)),
                        "$contactName को WhatsApp पर यह भेज दूँ: \"${short(r.message)}\"?")
                    // नवंबर 2026: फ़ाइल सीधे उसी की चैट में (jid) - WhatsApp की Send स्क्रीन पर आख़िरी "भेजें" पूछकर
                    SocialAction.SEND_FILE -> {
                        val m = media!!
                        val label = m.name.ifBlank { "फ़ाइल" }
                        SocialTask(id, r.platform, r.action, r.mode,
                            OpenSpec.ShareMedia(r.platform.pkg, m.mime, m.uri, emptyList(), r.message, jid = "$phoneDigits@s.whatsapp.net"),
                            listOf(PlanStep.Final(S.WA_SEND, listOfNotNull(m.name.substringBeforeLast('.').take(24).takeIf { it.length >= 3 }), S.WA_SEND)),
                            "$contactName को WhatsApp पर यह फ़ाइल भेज दूँ: \"${short(label)}\"?")
                    }
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
    /** 16 नवंबर 2026: स्पेशलिस्ट ने जो चुना/लिखा (फोटो, कैप्शन, हैशटैग, गाना, टैग) - वह सब सुनाकर आख़िर में "हाँ" पूछो */
    private fun ask(r: SocialRequest, plain: String): String = if (r.confirmExtra.isBlank()) plain
        else "${r.platform.label} पर सब तैयार है - ${r.confirmExtra}। " + when {
            r.action == SocialAction.POST_STORY -> "स्टोरी में डाल दूँ?"
            r.platform == Platform.YOUTUBE -> "Short अपलोड (पब्लिक) कर दूँ?"
            else -> "पब्लिक (शेयर) कर दूँ?"
        }

    private fun kind(m: SharedItem) = if (m.mime.startsWith("video/")) "वीडियो" else "फोटो"
    private fun short(s: String) = if (s.length > 80) s.take(77) + "…" else s
    private fun captionPart(c: String) = if (c.isEmpty()) "" else " (कैप्शन: \"${short(c)}\")"
}
