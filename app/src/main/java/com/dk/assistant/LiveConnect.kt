package com.dk.assistant

/**
 * v3.102: DK लाइव को DK के सारे हिस्सों से जोड़ना.
 *  - dk_features tool: DK लाइव को पता रहे कि DK के पास कौन-कौन से एजेंट/मोड हैं और हर एक में क्या है
 *  - story_task: स्टोरी मोड बोलकर (कहानी पूरी होते ही DK लाइव खुद बताए)
 *  - teacher_mode: टीचर मोड बोलकर खोलना/बंद करना
 *  - वेबसाइट बनकर तैयार/फ़ेल = DK लाइव खुद बताए
 *  - "मुझे देखो" = आगे वाला कैमरा, "ये देखो / पीछे वाला" = पीछे वाला
 * Android import नहीं -> JVM test.
 */
object LiveConnect {

    /** DK लाइव के dk_features tool का जवाब - सच्ची सूची (कौन सा tool किस काम का) */
    fun features(autoOn: Boolean, autoKinds: List<String>, oneKey: Boolean): String = buildString {
        appendLine("DK's real abilities (each line: what it is -> which tool you use). Use this list to answer 'तुम क्या-क्या कर सकती हो' and to route any task:")
        appendLine("1. Phone control -> phone_task: open apps, settings, alarms/timers, maps & navigation, SMS, normal calls, music, any multi-step action on the phone.")
        appendLine("2. WhatsApp -> whatsapp_task: send messages, WhatsApp voice call and video call to a saved contact.")
        appendLine("3. Calls from any app (SIM, WhatsApp, Instagram, Facebook, Messenger) -> phone_call: you announce who is calling and ask उठाऊँ या काट दूँ; answer/reject ONLY after the user says so.")
        appendLine("4. Instagram / Facebook posting -> social_media_task (+ social_media_task_control to confirm).")
        appendLine("5. Coding Agent (team of 11 on the user's GitHub repo: Manager, Planner, Coder, Reviewer, Tester, Debugger, Git, Prompt Engineer, Performance, QA, Deployment, plus an Inspector for repo/PR status) -> coding_task.")
        appendLine("6. DK Computer (autonomous background agent: web research, reading sites, calculations, PDF/files/tables/reports, reminders or tasks for later, chains of phone actions) -> computer_task.")
        appendLine("7. Website Forge (builds a full animated website live on the phone) -> build_website.")
        appendLine("8. Story Mode (Story Writer Agent: long emotional Hindi stories of 10, 15, 20, 25 or 30 minutes, written part by part) -> story_task.")
        appendLine("9. Teacher Mode (whiteboard page; what you explain is written there) -> teacher_mode. It also opens by itself when the user asks a study question.")
        appendLine("10. Autonomous Mode: " + (if (autoOn) "ON" + (if (autoKinds.isNotEmpty()) " for: " + autoKinds.joinToString(", ") else "") + " - phone_task requests of these kinds are planned and done by it automatically."
            else "OFF - the user can turn it on in Settings -> ऑटोनोमस मोड. Never claim it is on."))
        appendLine("11. Camera inside DK -> camera (front = look at the user, back = look at what the user shows). Screen share -> screen_share.")
        appendLine("12. Web search -> web_search, hard questions -> deep_think, time -> current_time, nearby/weather/location -> local_info.")
        appendLine("13. Notifications -> notifications (read) and notification_reply (reply).")
        appendLine("14. Memory -> remember_fact, recall_conversation, forget_memory.")
        appendLine("15. Status of background work (coding team / DK Computer) -> task_status. When coding, DK Computer, story or website work ends you get a SYSTEM NOTE with the real result - tell the user.")
        appendLine("API key: " + (if (oneKey) "one all-rounder key is used ONLY for talking, chat, web search and phone tasks. coding_task, computer_task, build_website and story_task do NOT run on one key - tell the user to turn off 'एक key से सब' in Settings -> AI दिमाग and add separate keys." else "each part uses its own key from Settings (or the main key)."))
        append("Tell the user only what is in this list. Never claim an ability that is not here.")
    }

    /** स्टोरी के मिनट: 10/15/20/25/30 में से सबसे पास वाला. न बताए (0) = 10 (स्टोरी स्क्रीन का अपना default) */
    fun storyMinutes(asked: Int): Int {
        if (asked <= 0) return 10
        return StoryPlan.LENGTHS.minByOrNull { kotlin.math.abs(it - asked) } ?: 10
    }

    fun storyStarted(topic: String, minutes: Int): String =
        "story_started: the Story Writer Agent is now writing a $minutes-minute Hindi story" +
            (if (topic.isBlank()) " (it picks a good topic itself)" else " on \"${topic.take(120)}\"") +
            ". The Story screen is open and parts appear there one by one. Tell the user briefly that it has started. You will get a SYSTEM NOTE when it is really finished - do not say it is ready before that."

    const val STORY_BUSY = "story_busy: a story is already being written right now. Tell the user to let it finish first (they can see it on the Story screen)."

    /** कहानी खत्म/रुकी -> DK लाइव को सच्चा नतीजा */
    fun storyDoneNote(title: String, parts: Int, total: Int, error: String?): String =
        if (error == null && total > 0 && parts >= total)
            "SYSTEM NOTE: the Story Writer Agent has really finished the story" + (if (title.isNotBlank()) " \"${title.take(100)}\"" else "") +
                " ($parts parts). It is on the Story screen (copy/save there). Tell the user briefly in the reply language that the story is ready. Do not read the whole story unless they ask."
        else "SYSTEM NOTE: the story is NOT complete - $parts of $total parts written. Problem: \"${(error ?: "रुक गई").take(200)}\". Tell the user this briefly. Do NOT say the story is ready."

    /** वेबसाइट तैयार/फ़ेल -> DK लाइव को सच्चा नतीजा */
    fun webDoneNote(ok: Boolean, prompt: String, error: String?): String =
        if (ok) "SYSTEM NOTE: Website Forge has really finished the website (\"${prompt.take(100)}\"). It is shown on the Forge screen. Tell the user briefly that the website is ready."
        else "SYSTEM NOTE: Website Forge could NOT finish the website (\"${prompt.take(100)}\"). Problem: \"${(error ?: "-").take(200)}\". Tell the user this briefly. Do NOT say it is ready."

    fun teacherResult(on: Boolean, wasOpen: Boolean): String = when {
        on -> "teacher_opened: Teacher Mode page is open. Whatever you explain now is written on it. Tell the user briefly and ask what to study."
        wasOpen -> "teacher_closed: Teacher Mode page closed. Tell the user briefly."
        else -> "Teacher Mode was not open."
    }

    private val ME = listOf(
        "मुझे देख", "मुझको देख", "मेरे को देख", "मेरा चेहरा", "मेरी शक्ल", "मुझे दिख", "मैं कैसा", "मैं कैसी", "मेरी तरफ़", "मेरी तरफ",
        "मुझे देखो", "mujhe dekh", "mujhko dekh", "mera chehra", "look at me", "see me",
        "مجھے دیکھ", "میرا چہرہ", "مجھ کو دیکھ",
        "आगे वाला", "आगे का कैमरा", "फ्रंट", "फ़्रंट", "सेल्फी", "सेल्फ़ी", "front", "selfie",
    )
    private val THING = listOf(
        "पीछे वाला", "पीछे का कैमरा", "बैक कैमरा", "बैक वाला", "back camera", "rear", "پیچھے",
        "ये देखो", "यह देखो", "इसे देखो", "इसको देखो", "ये क्या है", "यह क्या है", "ye dekho", "yeh dekho", "یہ دیکھو",
    )

    /**
     * कौन सा कैमरा: true = आगे (user को देखना), false = पीछे, null = पता नहीं (पहले वाला ही).
     * पहले model का facing, फिर user की अपनी बात ("मुझे देखो" = आगे).
     */
    fun wantFront(facing: String, userText: String): Boolean? {
        val f = facing.lowercase().trim()
        when {
            f.contains("front") || f.contains("selfie") || f.contains("आगे") || f.contains("सेल्फ़ी") || f.contains("सेल्फी") || f == "user" || f == "me" -> return true
            f.contains("back") || f.contains("rear") || f.contains("पीछे") -> return false
        }
        val u = userText.lowercase()
        if (u.isBlank()) return null
        if (THING.any { u.contains(it) }) return false
        if (ME.any { u.contains(it) }) return true
        return null
    }
}
