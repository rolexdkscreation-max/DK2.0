package com.dk.assistant

/**
 * v3.102: DK लाइव को DK के सारे हिस्सों से जोड़ना.
 *  - dk_features tool: DK लाइव को पता रहे कि DK के पास कौन-कौन से एजेंट/मोड हैं और हर एक में क्या है
 *  - story_task: स्टोरी मोड बोलकर (कहानी पूरी होते ही DK लाइव खुद बताए)
 *  - teacher_mode: टीचर मोड बोलकर खोलना/बंद करना
 *  - dk_settings: DK की सेटिंग्स जानना / खोलना / (दो बार पक्का करके) ऑन-ऑफ
 *  - वेबसाइट बनकर तैयार/फ़ेल = DK लाइव खुद बताए
 *  - "मुझे देखो" = आगे वाला कैमरा, "ये देखो / पीछे वाला" = पीछे वाला
 * Android import नहीं -> JVM test.
 */
object LiveConnect {

    /** DK लाइव के dk_features tool का जवाब - सच्ची सूची (कौन सा tool किस काम का) */
    fun features(autoOn: Boolean, autoKinds: List<String>, oneKey: Boolean): String = buildString {
        appendLine("DK's real abilities (each line: what it is -> which tool you use). Use this list to answer 'तुम क्या-क्या कर सकती हो' and to route any task:")
        appendLine("1. Phone control -> phone_task: open apps, settings, alarms/timers, maps & navigation, SMS, normal calls, music, any multi-step action on the phone.")
        appendLine("2. WhatsApp -> whatsapp_task: send messages, send a file DK finds on the phone (photo, video, PDF, document, a website/game DK made: action=send_file), WhatsApp voice call and video call to a saved contact. " +
            "WhatsApp CHAT MODE (चैटिंग मोड / WhatsApp मोड ऑन: DK keeps telling who wrote what, asks 'बॉस, क्या जवाब दूँ? या मैं खुद कर दूँ?', types exactly what he dictates, replies itself only when he says so; when 2-3 people write at once it first asks whom to talk to) -> action=chat_mode_on. " +
            "Current location (send_location), live location (send_live_location), a photo clicked from the WhatsApp camera (send_camera_photo), someone's number (send_contact).")
        appendLine("3. Calls from any app (SIM, WhatsApp, Instagram, Facebook, Messenger) -> phone_call: you announce who is calling and ask उठाऊँ या काट दूँ; answer/reject ONLY after the user says so.")
        appendLine("4. Instagram / Facebook posting -> social_media_task (+ social_media_task_control to confirm).")
        appendLine("5. Coding Agent (team of 7 on the user's GitHub repo: Manager (also understands the task), Planner (root cause first), Coder, Reviewer (also Performance), Tester (also QA), Debugger, Git/Release (Git + Deployment), plus a separate Explainer and an Inspector for repo/PR status) -> coding_task.")
        appendLine("6. DK Computer (autonomous background agent: web research, reading sites, calculations, PDF/files/tables/reports, reminders or tasks for later, chains of phone actions) -> computer_task.")
        appendLine("7. Website Forge (first asks what kind of site, name, sections, style, contact; then builds a full professional animated website live on the phone) -> build_website. Change it by voice / open it in Chrome from the phone's own local server / give or send its link on WhatsApp / deploy it to the internet (ONLY when the user asks) -> website_action.")
        appendLine("7a. App Studio (apps built right on the phone - web app, PWA, Android/Flutter app - with a live preview; DK Computer and the Coding Agent both build there) -> app_studio: status = what is being built now, which step is running, files, result; open = show the studio screen; change = change the current app; stop; build = a new app. task_status also shows it.")
        appendLine("7b. Games -> make_game: DK Computer makes a playable HTML5 game exactly as asked; DK opens it in Chrome and tells what is in it.")
        appendLine("8. Story Mode (Story Writer Agent: long emotional Hindi stories of 10, 15, 20, 25 or 30 minutes, written part by part) -> story_task.")
        appendLine("9. Teacher Mode (whiteboard page; what you explain is written there) -> teacher_mode. It opens ONLY when the user himself asks (टीचर मोड खोलो) - never by itself for a study question.")
        appendLine("10. Autonomous Mode: " + (if (autoOn) "ON" + (if (autoKinds.isNotEmpty()) " for: " + autoKinds.joinToString(", ") else "") + " - phone_task requests of these kinds are planned and done by it automatically."
            else "OFF - the user can turn it on in Settings -> ऑटोनोमस मोड. Never claim it is on."))
        appendLine("10b. Screen Guard (स्क्रीन गार्ड / गार्ड मोड, anti-theft) ON -> screen_guard (only ON; OFF only by the owner: unlock phone / face / secret word).")
        appendLine("11. Camera inside DK -> camera (front = look at the user, back = look at what the user shows). Screen share -> screen_share. One look at the phone screen when he asks about it (which product, its colour, price, brand/company, offers, rating; what is written there) -> look_screen.")
        appendLine("12. Web search -> web_search, hard questions -> deep_think, time -> current_time, nearby/weather/location -> local_info.")
        appendLine("13. Notifications -> notifications (read) and notification_reply (reply).")
        appendLine("14. Memory -> remember_fact, recall_conversation, forget_memory.")
        appendLine("15. Status of background work (coding team / DK Computer / App Studio) and of the on-screen social review (\"रिव्यू कर रहे हो?\") -> task_status - say yes ONLY if it says RUNNING. When coding, DK Computer, story or website work ends you get a SYSTEM NOTE with the real result - tell the user.")
        appendLine("16. DK's own settings -> dk_settings: list every setting with its state, open any part of Settings, switch settings ON/OFF (always two confirmations: \"ठीक है बॉस\" + \"सर, क्या सच में...?\").")
        appendLine("17. DK Inspector (app's own debugger) -> dk_inspector: list found bugs, full report, answer questions from real debug logs, run an inspection now. It never changes code; a report goes to the Coding Agent ONLY after the owner clearly says yes (\"ठीक है, Coding Agent को दे दो\").")
        appendLine("18. Image generation -> image_task (you write a professional detailed English prompt from the user's idea, plus his exact words in user_words): DK opens the real ChatGPT or Gemini app, makes the picture, downloads it to the gallery and really looks at it to tell what it shows. Any further work inside that app -> phone_task.")
        appendLine("19. ChatGPT / Gemini app (not an image) -> ai_app_prompt: DK types ANY prompt/question into the real app, presses Send itself and reads the answer back. Never say DK cannot tap or send - DK can.")
        appendLine("19b. Whole conversation with ANY AI app (ChatGPT, Claude, Gemini, Perplexity, Copilot, DeepSeek, Grok, Meta AI or any other app by name) -> ai_talk: DK opens it ON SCREEN and talks with it by itself - debate (मुकाबला), question-answer, learning its methods (learn: saved forever and used by DK later), or getting a task done. DK answers that AI's questions itself; the user only watches. The real summary comes as a SYSTEM NOTE. रोको = ai_talk action=stop.")
        appendLine("14b. Memory never auto-deletes: every conversation stays on the phone forever (only the owner's \"भूल जाओ\" deletes); recall_conversation searches ALL of it.")
        appendLine("20. Shopping (\"मुझे शर्ट / जींस / जूते / घड़ी देखनी है\") -> shop_search: DK checks which shopping apps (Flipkart, Meesho, Amazon, Myntra, Ajio) are on the phone, asks \"किस ऐप पर दिखाऊँ?\" if more than one, then opens that app and searches exactly that item. Never orders or pays.")
        appendLine("21. Instagram / Facebook / YouTube REVIEW (views per reel, why reach falls, what works, trends, how to grow) -> social_insights: works WITHOUT any connector - DK opens the real app / its dashboard / YouTube Studio ON SCREEN in front of the user (he watches every step; read-only), plus the official API if connected, then gives a full report. Never say it works in the background - unless the user himself asked for background (then background=true: no app opens, only the official API, progress in the top notification). Takes a few minutes; the real summary comes as a SYSTEM NOTE. " +
            "Questions about his numbers later (followers, reach, last video views, most popular video) -> social_insights action=ask (instant, from the saved real numbers). " +
            "It also reads the profile (name, followers, following), the latest reel and its comments, and the monetization / recommendation status with the reason shown in the app. " +
            "If the user says रिव्यू रोक दो / रुको / stop, call social_insights with action=stop at once (DK also stops it by itself) and confirm only after the tool says STOPPED. " +
            "If while it runs the user asks for another phone task, just do that task: the review pauses itself and continues on screen by itself afterwards - say exactly that. " +
            "Never state a monetization / ban / recommendation reason that the review did not actually read.")
        appendLine("22. Reel / Shorts / video watching record (last 7 days: per day, per app, today vs yesterday, daily limit) -> reel_stats. Answer only from its exact counts (counted only while Accessibility is ON). If he crossed his limit or watched more than yesterday, gently suggest a break (eyes, sleep, focus).")
        appendLine("API key: " + (if (oneKey) "one all-rounder key runs ALL work: talking, chat, web search, phone tasks, image_task, coding_task, computer_task, story_task and build_website (the Website Builder's own key is used first if it has one). Just call the tool - the tool reply tells the truth (only if the key is missing it says so)." else "each part uses its own key from Settings (or the main key). build_website uses ONLY the Website Builder's own key (Settings -> AI दिमाग -> वेबसाइट बिल्डर)."))
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
        if (ok) "SYSTEM NOTE: Website Forge has really finished the website (\"${prompt.take(100)}\"). It is shown on the Forge screen. Tell the user briefly that the website is ready, and that he can ask for changes, to open it in Chrome, to send its link, or to deploy it (website_action)."
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
