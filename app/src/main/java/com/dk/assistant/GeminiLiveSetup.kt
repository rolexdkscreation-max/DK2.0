package com.dk.assistant

import com.dk.assistant.GeminiLive.Companion.FALLBACK_MODELS
import com.dk.assistant.GeminiLive.Companion.FAST_START
import com.dk.assistant.GeminiLive.Companion.MOOD_TAG_NOTE
import com.dk.assistant.GeminiLive.Companion.PREFIX_PADDING_MS
import com.dk.assistant.GeminiLive.Companion.SERVER_SILENCE_MS
import com.dk.assistant.GeminiLive.Companion.SPLIT_NOTE
import com.dk.assistant.GeminiLive.Fatal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// GeminiLive का हिस्सा: Live का setup message और मॉडल की सूची (कौन सा मॉडल, कौन से tools, आवाज़, भाषा).
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

/** Network se Live models ki list (aur cache memory + phone dono me) */
internal fun GeminiLive.fetchModels(key: String): List<String> {
    val found = mutableListOf<String>()
    val c = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()   // v5.1: वही साझा pool
    val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
        .header("x-goog-api-key", key).build()
    c.newCall(req).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (r.code == 400 || r.code == 401 || r.code == 403) throw Fatal("Gemini key गलत है")
        val arr = JSONObject(s).optJSONArray("models")
        for (i in 0 until (arr?.length() ?: 0)) {
            val m = arr!!.optJSONObject(i) ?: continue
            if (m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("bidiGenerateContent") == true)
                found.add(m.optString("name").removePrefix("models/"))
        }
    }
    if (found.isNotEmpty()) {
        val now = System.currentTimeMillis()
        modelCache = Triple(key.hashCode(), now, found.toList())
        Prefs.saveLiveModelCache(svc, LiveModelCache.encode(key.hashCode(), now, found))
    }
    return found
}

/** Is key par kaun se Live models hain (bidiGenerateContent) - sabse naya/tez pehle */
internal fun GeminiLive.discoverModels(key: String): List<String> {
    val found = mutableListOf<String>()
    // wake word par har baar list mat mango (deri). Cache ab phone me bhi save - app restart ke baad bhi
    // connect se pehle 3-4s ki list wali deri nahi (log 07:51:50); 1 ghante se purani ho to nayi peeche se.
    val kh = key.hashCode()
    val cache = modelCache ?: LiveModelCache.decode(Prefs.liveModelCache(svc))?.also { modelCache = it }
    when (LiveModelCache.plan(cache, kh, System.currentTimeMillis())) {
        "use" -> found.addAll(cache!!.third)
        "refresh" -> {
            found.addAll(cache!!.third)
            svc.scope.launch(Dispatchers.IO) {
                try { fetchModels(key) } catch (e: Exception) { log("models list (peeche se) nahi aayi: ${e.message}") }
            }
        }
        else -> try { found.addAll(fetchModels(key)) } catch (e: Fatal) { throw e } catch (e: Exception) { log("models list nahi aayi: ${e.message}") }
    }
    // v3.41: sabse NAYA nahi, sabse TEZ pehle - phone par naapa jawab-samay (LiveModelRank)
    val lat = Prefs.liveLatency(svc)
    // नवंबर 2026: 2.5 native-audio (Google की तरफ़ 1011) सिर्फ़ बैकअप; मालिक ने चुना हो तो LiveModelPick उसे फिर पहले रखता है
    val all = LiveStay.demoteKnown1011(LiveModelRank.order(found, FALLBACK_MODELS, lat))
    // limit khatam wale model (aaj) chhodo - har baar 2 bekaar connection = ~1.5s deri + mic ke tukde gire
    val (skip, ok) = all.partition { Cooldown.blocked(LiveKeys.cooldownName(keyIdx, it)) }
    if (skip.isNotEmpty()) log("limit खत्म, छोड़े: " + skip.joinToString { m -> m + " (" + ((Cooldown.until(LiveKeys.cooldownName(keyIdx, m)) - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + " मिनट बाकी)" })
    log("सबसे तेज़ क्रम: " + ok.take(3).joinToString { m -> m + (lat[m]?.let { " ${it}ms" } ?: "") })
    return ok.ifEmpty { all }
}

/** नवंबर 2026: handle के बिना नया सत्र (नई बातचीत) = पिछली कुछ बातें prompt में ([LiveCarry]); handle हो तो server याद रखता है - कुछ नहीं */
internal fun GeminiLive.carryNote(resume: Boolean): String {
    if (resume && handle != null) return ""
    return try {
        LiveCarry.note(DKState.messages.map { LiveCarry.Turn(it.role, it.text, it.time) }, System.currentTimeMillis())
            .also { if (it.isNotEmpty()) log("Live: नया सत्र (handle नहीं) - पिछली बातचीत prompt में जोड़ी (${it.length} अक्षर)") }
    } catch (_: Exception) { "" }
}

/** v5.1: txt = यह जोड़ी TEXT माँगे (दौड़ में हर मॉडल का अपना); resume = पुरानी बातचीत का handle भेजो (सिर्फ़ उसी जोड़ी पर) */
internal fun GeminiLive.setupJson(m: String, txt: Boolean = connText, resume: Boolean = true): String {
    NetProbe.refresh(svc)      // v3.88: धीमा नेटवर्क = छोटे जवाब (अपने-आप, कोई सेटिंग नहीं)
    val brain = BrainManager(svc)
    val ex = extrasFor(m)
    val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(JSONObject().put("name", "phone_task")
            .put("description", "Do something on the user's Android phone: open apps (including opening WhatsApp), call, SMS, YouTube, music, settings (wifi, bluetooth, torch, volume, brightness), maps/directions, shopping, read or tap the screen. NOT for a single WhatsApp message - use whatsapp_task (action=send_message) for that. BUT if the user gives SEVERAL tasks in one go (e.g. alarm, then WhatsApp, then delete photo, then YouTube, then maps), call phone_task ONCE with his full request - the phone runs them one by one in order. While tasks run: a new task = phone_task with only that task (added at the end); 'रुको'/'बंद करो' = phone_task with request 'रुको' (stops at once).")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("request", JSONObject().put("type", "STRING").put("description", "The user's request in their own words")))
                .put("required", JSONArray().put("request"))))
        .put(JSONObject().put("name", "web_search")
            .put("description", "Live search RIGHT NOW: searches several websites at once (official government websites first for government questions, plus reputed news and reference sites), cross-checks them and returns one verified, up-to-date answer with the website names (SOURCES). Use it for anything live or changing: news, prices, weather, scores, government schemes/rules/forms/dates, results, recruitments, people in office, products, releases - and anything you are not sure about. Whenever you say you will search / check / look it up, you MUST call this in the SAME turn - never only promise. (Opening a website or searching inside Chrome/an app on the phone = phone_task.)")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "STRING").put("description", "What to search, with all details the user gave")))
                .put("required", JSONArray().put("query"))))
        .put(JSONObject().put("name", "current_time")
            .put("description", "Get the exact current date, day and time (internet-checked clock, user's time zone). Call it whenever the user asks the time, date or day, or before saying how late/early it is - never guess.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
        .put(JSONObject().put("name", "deep_think")
            .put("description", "Solve a genuinely HARD question with a much stronger reasoning model (multi-step maths, logic puzzles, code, detailed comparisons, planning, exam-level or deep why/how questions). Returns a carefully reasoned answer in Hindi to speak.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("question", JSONObject().put("type", "STRING").put("description", "The complete question with all details the user gave")))
                .put("required", JSONArray().put("question"))))
        .put(JSONObject().put("name", "coding_task")
            .put("description", "Give a programming task to the user's coding team (Builder + Reviewer + Coordinator) that works on their GitHub repo - e.g. build/change/fix a feature in the app. Also use it when the user approves or rejects a coding suggestion (\"सुझाव जोड़ दो\" / \"सुझाव नहीं\"), and when the user asks to merge the ready pull request (e.g. PR merge कर दो - pass task \"PR merge कर दो\"; it checks tests/build first, merges, then GitHub Actions builds the new APK release). Also use it when the user asks about the repository itself - what is going on, which PRs are open, whether a PR/build is stuck or failed, 'रिपो चेक करो', 'PR 42 ठीक करो', 'PR 42 merge करो' - pass the user's words; it reads the real GitHub state and answers. Also use it when the coding team showed its understanding and the user says yes / corrects it / answers its question - pass exactly what the user said (e.g. \"हाँ\"). It starts in the background; tell the user it has started.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The full coding task (or the suggestion decision) in the user's words")))
                .put("required", JSONArray().put("task"))))
        .put(JSONObject().put("name", "computer_task")
            .put("description", "Give a multi-step task to DK Computer, the user's autonomous agent: web research, reading websites, calculations, making files/PDF/tables/reports, reminders or tasks at a later time (survive phone restart), and chains of phone actions. Use it when the user says DK कंप्यूटर / कंप्यूटर से, or asks to research and make a PDF/file/report/table, or to remind/do something later. Also use it to pass the user's हाँ/नहीं when DK Computer asked for permission (task = the user's words). It runs in the background, shows each step on the DK कंप्यूटर screen and notifies when done; tell the user it has started.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The full task in the user's words, with all details (or just हाँ / नहीं for a permission)")))
                .put("required", JSONArray().put("task"))))
        .put(JSONObject().put("name", "build_website")
            .put("description", "DK Website Forge: build a complete professional animated website from the user's description (e.g. 'DK, ek cafe ki website banao'). A live 3D build screen opens on the phone and the finished website is shown there. Use it ONLY when the user asks to build/create/make a website, web page or landing page - not for coding tasks on GitHub. FIRST get the details: what kind of site, name, sections, colours/style, contact - ask him the missing ones one by one (if he says you decide, decide yourself), then call with all fields and details_done=true. If details are missing it returns need_details (NOT started) - ask those questions. It returns forge_started when building really starts; tell the user it has started. For changes / showing / sending / deploying an existing website use website_action.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("prompt", JSONObject().put("type", "STRING").put("description", "What website to build, with every detail the user gave (business, name, sections, colours, language)"))
                    .put("site_type", JSONObject().put("type", "STRING").put("description", "What the site is for (cafe, shop, portfolio, coaching ...)"))
                    .put("business_name", JSONObject().put("type", "STRING").put("description", "Name shown on the site"))
                    .put("sections", JSONObject().put("type", "STRING").put("description", "Sections wanted, e.g. menu, services, gallery, prices, reviews, contact"))
                    .put("style", JSONObject().put("type", "STRING").put("description", "Colours and style"))
                    .put("contact", JSONObject().put("type", "STRING").put("description", "Phone / WhatsApp / address / email to show"))
                    .put("details_done", JSONObject().put("type", "BOOLEAN").put("description", "true = the user gave the details or said you decide")))
                .put("required", JSONArray().put("prompt"))))
        .put(JSONObject().put("name", "website_action")
            .put("description", "Work on the website DK already built (Website Forge): edit = change something on it by voice ('header blue kar do', 'menu me chai add karo'); open = run it on the phone (local server) and open it in Chrome; link = give its local link; share = send its link (or the file itself with as_file=true) to a contact on WhatsApp; deploy = put it on the internet (GitHub Pages) - ONLY when the user clearly says deploy / publish / online daalo, never on your own.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("edit").put("open").put("link").put("share").put("deploy")))
                    .put("change", JSONObject().put("type", "STRING").put("description", "edit: exactly what to change, in the user's words"))
                    .put("contact", JSONObject().put("type", "STRING").put("description", "share: whom to send it to"))
                    .put("as_file", JSONObject().put("type", "BOOLEAN").put("description", "share: send the HTML file instead of the link"))
                    .put("user_words", JSONObject().put("type", "STRING").put("description", "deploy: the user's exact words asking to deploy")))
                .put("required", JSONArray().put("action"))))
        .put(JSONObject().put("name", "make_game")
            .put("description", "Make a game: DK Computer builds a complete playable HTML5 game exactly as the user describes (snake, car race, quiz, puzzle ...), then DK opens it in Chrome by itself and you get a SYSTEM NOTE with what is in the game - then tell the user चेक कर लीजिए and the features. Use when the user asks to make/build a game (गेम बनाओ). For a change in the last game set change=true. It returns game_started immediately.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("description", JSONObject().put("type", "STRING").put("description", "The game exactly as the user wants it, with every detail (or the change)"))
                    .put("change", JSONObject().put("type", "BOOLEAN").put("description", "true = change the last game instead of a new one")))
                .put("required", JSONArray().put("description"))))
        .put(JSONObject().put("name", "dk_features")
            .put("description", "Get the REAL list of everything DK can do - all agents and modes (phone control, WhatsApp, calls, social media, Coding Agent team, DK Computer, Website Forge, Story Mode, Teacher Mode, Autonomous Mode, camera, screen share, search, memory) and which tool does what. Call it when the user asks what you can do / which agents DK has / what is inside an agent (तुम क्या-क्या कर सकती हो, कौन-कौन से एजेंट हैं), or when you are unsure which tool fits a task.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
        .put(JSONObject().put("name", "story_task")
            .put("description", "Story Mode: the Story Writer Agent writes a long emotional Hindi story (10, 15, 20, 25 or 30 minutes) part by part on DK's Story screen. Use it when the user asks to write/make a story or kahani (कहानी लिखो, 20 मिनट की कहानी बनाओ, स्टोरी मोड). It returns story_started immediately; you get a SYSTEM NOTE when it is really finished.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("topic", JSONObject().put("type", "STRING").put("description", "The story topic / instructions in the user's words (empty = the agent picks one)"))
                    .put("minutes", JSONObject().put("type", "INTEGER").put("description", "Length in minutes if the user said it (10-30), else 0")))))
        .put(JSONObject().put("name", "teacher_mode")
            .put("description", "Open (on=true) or close (on=false) DK Teacher Mode - a whiteboard page where everything you explain is written for studying. Use it ONLY when the user himself asks: टीचर मोड खोलो / teacher mode on / पढ़ाई मोड चालू करो / टीचर मोड बंद करो. NEVER open it by yourself just because a question is about studies (owner's rule).")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                .put("required", JSONArray().put("on"))))
        // v5 (मालिक): DK को सारी सेटिंग्स पता हों और वह उन्हें ऑन-ऑफ कर सके - हर बदलाव से पहले दो बार पक्का
        .put(JSONObject().put("name", "dk_settings")
            .put("description", "Know, open and switch DK's own settings. action=list: all switchable settings with their current ON/OFF state and all parts of Settings. " +
                "action=open: open that part of Settings on screen (setting = what to open, e.g. 'आवाज़', 'DK कंप्यूटर', 'API key'). " +
                "action=set: switch a setting ON (on=true) or OFF (on=false). Owner's rule - TWO confirmations: the first set call never changes anything, it tells you to say " +
                "\"ठीक है बॉस\" and ask \"सर, क्या आप सच में ... ऑन/ऑफ करना चाहते हैं?\"; only after his clear yes call set again with confirmed=true. Use it whenever the user asks " +
                "about DK's settings or says 'यह सेटिंग ऑन/ऑफ कर दो' / 'सेटिंग खोलो'.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("list").put("open").put("set")))
                    .put("setting", JSONObject().put("type", "STRING").put("description", "The setting in the user's words (empty for list)"))
                    .put("on", JSONObject().put("type", "BOOLEAN").put("description", "true = turn ON, false = turn OFF (only for set)"))
                    .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "true ONLY after you asked 'सर, क्या सच में...?' and he clearly said yes")))
                .put("required", JSONArray().put("action"))))
        // v5.1: DK Inspector - app की अपनी जाँच (सिर्फ़ पढ़ना). Coding Agent को रिपोर्ट सिर्फ़ मालिक की साफ़ हाँ के बाद
        .put(JSONObject().put("name", "dk_inspector")
            .put("description", "DK Inspector - DK's own debugger that reads the real Debug data (Gemini Live status, latency breakdown, logs). " +
                "action=list: found bug reports with id/severity/status. action=report: the full report of one id (explain it in simple Hindi; 'suspected' causes stay 'शायद'). " +
                "action=ask: answer the owner's question about bugs/slowness/mic/connection from real data (question = his words). action=inspect: run a deep inspection now. " +
                "action=send_to_coder: give the report to the Coding Agent - ONLY after the owner clearly says yes (e.g. 'ठीक है, Coding Agent को दे दो'), then approved=true. " +
                "action=dismiss: remove one report (owner says 'हटा दो' / 'ठीक हो गया'); if it comes back it is shown again. action=later: remind later (owner says 'बाद में'). " +
                "action=clear_all: clear the whole bug list - only when the owner asks to clear all. It watches the whole app (calls, connectors, update, power-button assistant, notifications, crashes, ANR) every 20 minutes. " +
                "Never claim a bug is fixed unless the report status is Resolved. Use it when the owner asks what is wrong with DK, why DK is slow/not hearing, or about a DKI- report.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("list").put("report").put("ask").put("inspect").put("send_to_coder").put("dismiss").put("later").put("clear_all")))
                    .put("id", JSONObject().put("type", "STRING").put("description", "Bug ID like DKI-1A2B3C (report / send_to_coder / dismiss / later)"))
                    .put("question", JSONObject().put("type", "STRING").put("description", "The owner's question in his words (ask)"))
                    .put("approved", JSONObject().put("type", "BOOLEAN").put("description", "true ONLY after the owner clearly said yes to giving it to the Coding Agent")))
                .put("required", JSONArray().put("action"))))
        .put(JSONObject().put("name", "social_media_task")
            .put("description", "Post on Instagram or Facebook by driving the real app on the phone. Instagram: post_photo, post_video, post_reel, post_story. Facebook: post_text, post_photo, post_video, post_story. Media can ONLY be a photo/video the user shared to DK (Gallery -> Share -> DK); use media='latest_shared'. mode='draft' fills everything but never posts. Posting is irreversible: you will get a SYSTEM NOTE with status waiting_confirmation - ask the user that exact question and only after a clear yes call social_media_task_control(command=confirm). DK never fills logins, passwords, CAPTCHAs or 2-step codes - it waits for the user. Report the final status truthfully (submitted_unverified = not confirmed).")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("platform", JSONObject().put("type", "STRING").put("enum", JSONArray().put("instagram").put("facebook")))
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("post_photo").put("post_video").put("post_reel").put("post_story").put("post_text")))
                    .put("caption", JSONObject().put("type", "STRING").put("description", "Caption / post text exactly as the user wants it"))
                    .put("media", JSONObject().put("type", "STRING").put("description", "latest_shared (default)"))
                    .put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray().put("publish").put("draft"))))
                .put("required", JSONArray().put("platform").put("action"))))
        // नवंबर 2026: इंस्टाग्राम / फेसबुक / यूट्यूब का पूरा रिव्यू (API + ऐप की स्क्रीन से असली आँकड़े, ट्रेंड, सलाह)
        .put(JSONObject().put("name", "social_insights")
            .put("description", "Full REVIEW of the user's own Instagram / Facebook / YouTube account: DK opens the app's dashboard / YouTube Studio on the screen in front of the user and reads every reel/video screen (read-only), plus the official API if a connector happens to be connected, " +
                "finds views per reel/video, why reach is falling, what works, the latest 3-4 reels, trending topics and how to get more views, then makes a full report. " +
                "Use for 'इंस्टाग्राम का रिव्यू करो', 'मेरी रील पर कितने व्यूज़ आए', 'रीच क्यों गिर रही है', 'यूट्यूब स्टूडियो चेक करो', 'तीनों का एनालिसिस'. Not for posting (social_media_task). " +
                "NO connector is needed (the screen method works alone). It runs ON THE SCREEN in front of the user for a few minutes - never call it background or hidden: say the returned line, then wait - you get a SYSTEM NOTE with the real summary; never invent numbers before that. action=stop stops it.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("platforms", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING").put("enum", JSONArray().put("instagram").put("facebook").put("youtube")))
                        .put("description", "Which platforms the user named; all three if he said 'सब / तीनों / सोशल मीडिया'"))
                    .put("request", JSONObject().put("type", "STRING").put("description", "The user's own words (what he wants to know)"))
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("start").put("stop")))))
        )
        .put(JSONObject().put("name", "image_task")
            .put("description", "Generate a picture with the REAL ChatGPT or Gemini app on the phone (DK opens the app, types the prompt, waits until the image is made, taps Download, then really looks at the image). " +
                "Use whenever the user asks to make / generate / create an image, photo, picture, logo, poster or wallpaper (इमेज बनाओ, फोटो बनाओ, ChatGPT/Gemini में इमेज बनाओ). " +
                "DK itself turns the idea into a professional prompt. Takes 30-120 seconds - first say one short line like 'बना रहा हूँ, थोड़ा समय लगेगा'. After it returns, tell ONLY what the result says the image shows - never invent it. " +
                "Not for editing a photo from the gallery.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("prompt", JSONObject().put("type", "STRING").put("description", "YOU write a professional, detailed ENGLISH image prompt from the user's idea (never just copy his words): subject + every detail he gave (names, colours, numbers, exact text to show in quotes), setting/background, style or medium, composition/camera angle, lighting, colour palette, mood, quality, aspect ratio. 60-120 words. Do not add subjects he did not ask for."))
                    .put("user_words", JSONObject().put("type", "STRING").put("description", "The user's own words for the picture, exactly as he said them"))
                    .put("app", JSONObject().put("type", "STRING").put("enum", JSONArray().put("chatgpt").put("gemini").put("auto")).put("description", "Which app the user named; auto = ChatGPT if installed, else Gemini"))
                    .put("download", JSONObject().put("type", "BOOLEAN").put("description", "Save it to the phone gallery (default true)")))
                .put("required", JSONArray().put("prompt"))))
        // v3.156: ChatGPT / Gemini में कुछ भी लिखकर भेजना (इमेज नहीं) - DK खुद Send दबाता है
        .put(JSONObject().put("name", "ai_app_prompt")
            .put("description", "Type ANY prompt or question into the REAL ChatGPT or Gemini app on the phone, press Send (DK taps it itself) and read the app's answer back. " +
                "Use when the user says e.g. 'ChatGPT में यह पूछो / लिखो / भेजो', 'Gemini ऐप से पूछो', 'ChatGPT पर prompt डालो'. For making a picture use image_task instead. " +
                "DK CAN tap and send in these apps - never tell the user that you cannot tap, touch or send. First say one short line like 'भेज रहा हूँ'.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("prompt", JSONObject().put("type", "STRING").put("description", "Exactly what to type in the app (the user's words / the prompt he wants)"))
                    .put("app", JSONObject().put("type", "STRING").put("enum", JSONArray().put("chatgpt").put("gemini").put("auto")).put("description", "Which app the user named; auto = ChatGPT if installed, else Gemini")))
                .put("required", JSONArray().put("prompt"))))
        // v3.156: "मुझे शर्ट देखनी है" - shopping ऐप (पूछकर) में वही चीज़
        .put(JSONObject().put("name", "shop_search")
            .put("description", "Show a product the user wants to see or buy (shirt, jeans, lower/track pants, shoes, watch, kurta, saree, bag...) in a shopping app on the phone. " +
                "Use for 'मुझे शर्ट देखनी है', 'जींस दिखाओ', 'जूते देखने हैं', 'Flipkart पर घड़ी दिखाओ'. Leave app empty when the user did not name one: DK then checks which apps are installed " +
                "and the result tells you to ask 'किस ऐप पर दिखाऊँ - Flipkart, Meesho या Amazon?'. After he answers call again with that app. First say 'ठीक है, दिखाता हूँ'. Never order or pay.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("item", JSONObject().put("type", "STRING").put("description", "What to search, in short English shopping words with every detail he said (colour, men/women, size, brand), e.g. 'black shirt for men'"))
                    .put("item_hindi", JSONObject().put("type", "STRING").put("description", "The item in the user's own Hindi word, e.g. 'शर्ट'"))
                    .put("app", JSONObject().put("type", "STRING").put("description", "Shopping app the user named: flipkart, meesho, amazon, myntra, ajio. Empty if he did not name one"))
                    .put("user_words", JSONObject().put("type", "STRING").put("description", "The user's own words exactly")))
                .put("required", JSONArray().put("item"))))
        .put(JSONObject().put("name", "whatsapp_task")
            .put("description", "WhatsApp on the phone - ALWAYS use this (never phone_task) to send a WhatsApp message or make a WhatsApp call: read_unread (unread messages from notifications), voice_call / video_call (contact - WhatsApp audio or video call; when the user says WhatsApp पर कॉल / वीडियो कॉल; a plain X को कॉल करो without WhatsApp is a normal phone call, not this), send_message (contact + message; irreversible - wait for waiting_confirmation SYSTEM NOTE, ask the user, confirm only after a clear yes), send_file (contact + file: DK itself finds the file on the phone - photo, video, PDF, document, or a website/game DK made - and sends it into that chat; file = which file in the user's words, e.g. 'आधार कार्ड वाली PDF', 'snake game', or latest_photo; same waiting_confirmation rule; file_ambiguous = ask which one; file_permission = tell him what the result says), read_chat (contact), search_chat (query), mute / unmute / mark_read (contact). If the contact is ambiguous you get contact_ambiguous - ask the user which one.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("read_unread").put("voice_call").put("video_call").put("send_message").put("send_file").put("read_chat").put("search_chat").put("mute").put("unmute").put("mark_read")))
                    .put("contact", JSONObject().put("type", "STRING").put("description", "Contact name as saved, or phone number"))
                    .put("message", JSONObject().put("type", "STRING"))
                    .put("file", JSONObject().put("type", "STRING").put("description", "send_file: which file, in the user's words (name / type / 'latest_photo')"))
                    .put("query", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("action"))))
        .put(JSONObject().put("name", "social_media_task_control")
            .put("description", "Control the running Instagram/Facebook/WhatsApp task: status, confirm (ONLY after the user clearly said yes to the confirmation question - DK checks this), cancel, resume (after the user finished a login/CAPTCHA/permission screen themselves).")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "STRING").put("enum", JSONArray().put("status").put("confirm").put("cancel").put("resume")))
                    .put("task_id", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("command"))))
        .put(JSONObject().put("name", "task_status")
            .put("description", "Get the REAL current status of background work given to DK Computer or the coding team (state, real steps done, latest step, result). Call it whenever the user asks how far a task has got / is it done (काम कहाँ तक पहुँचा, हुआ या नहीं). Tell ONLY what it returns - never guess progress or percentages.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
        .put(JSONObject().put("name", "phone_call")
            .put("description", "Handle the phone call that is ringing now (or is in progress) - ONLY after the user told you what to do. action: answer = pick up; answer_speaker = pick up and put it on loudspeaker; reject = cut / decline / hang up; speaker_on / speaker_off during a call; who = who is calling. Never answer or reject a call on your own.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("action", JSONObject().put("type", "STRING")
                    .put("description", "One of: answer, answer_speaker, reject, speaker_on, speaker_off, who")))
                .put("required", JSONArray().put("action"))))
        .put(JSONObject().put("name", "screen_share")
            .put("description", "Turn sharing of the user's phone screen with you ON or OFF (it is OFF by default). Only while it is ON you receive an image of the screen about once a second. If the user asks you to look at / read / check their screen and sharing is OFF, first ask 'स्क्रीन शेयर चालू करूँ?'; when they agree, or when they directly ask you to share or see the screen, call with on=true. Never turn it on by yourself without the user's clear request or yes. Android then shows its own permission popup and the USER must tap Start (DK never taps it). Never describe the screen before you get the SYSTEM NOTE that sharing really started. Call with on=false when the user says to stop.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                .put("required", JSONArray().put("on"))))
        .put(JSONObject().put("name", "camera")
            .put("description", "Turn the phone camera ON or OFF so you can see what the user shows you (OFF by default). Only while it is ON you receive a camera image about once a second. Use when the user says कैमरा चालू करो / कैमरा से देखो / ये देखो क्या है (and the camera is off), or to turn it off. This is DK's own camera inside the app (not the phone's camera app). facing: front when the user wants you to look at THEM (मुझे देखो / मेरा चेहरा देखो / मैं कैसा लग रहा हूँ / आगे वाला कैमरा); back when they want to show you something (ये देखो / पीछे वाला कैमरा / बैक कैमरा). If the camera is already on and the user wants the other side, call again with on=true and the new facing.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN"))
                    .put("facing", JSONObject().put("type", "STRING").put("description", "back or front")))
                .put("required", JSONArray().put("on"))))
        .put(JSONObject().put("name", "notifications")
            .put("description", "Read the phone's recent notifications (messages, WhatsApp, calls missed, apps). Call it when the user asks क्या notification आया / किसका मैसेज आया / क्या लिखा है / कोई मैसेज आया क्या. Optional app = app or sender name to filter (e.g. WhatsApp, Rahul). Tell the user briefly who sent what - only what it returns.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("app", JSONObject().put("type", "STRING").put("description", "Optional: app or sender name to filter")))))
        .put(JSONObject().put("name", "notification_reply")
            .put("description", "Send a reply to a notification directly from the notification (WhatsApp, SMS, Telegram, etc.), when the user asks to reply (इसको रिप्लाई कर दो / Rahul को जवाब दो कि ...). to = sender or app name from the notification, or empty for the latest one. message = exactly what the user wants to send, in their words. After it returns, tell the user in one short line what was sent and to whom.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("to", JSONObject().put("type", "STRING").put("description", "Sender/app name, or empty for the newest notification"))
                    .put("message", JSONObject().put("type", "STRING").put("description", "The reply text to send")))
                .put("required", JSONArray().put("message"))))
        .put(JSONObject().put("name", "forget_memory")
            .put("description", "When the user says to forget something (यह भूल जाओ / वो बात भूल जाओ / इसे मिटा दो), call this with what to forget - resolve 'यह/वो' from the conversation into the actual topic in a few words (e.g. 'इंटरव्यू', 'प्रिया का नंबर'). Deletes it from saved facts and conversation memory. Then confirm in one short line.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("about", JSONObject().put("type", "STRING").put("description", "The topic to forget, a few words")))
                .put("required", JSONArray().put("about"))))
        .put(JSONObject().put("name", "recall_conversation")
            .put("description", "The user asks what HE said earlier or what you two talked about (मैंने क्या बोला था / अभी मैंने क्या कहा था / कल क्या बात हुई थी / मैंने उस बारे में क्या बताया था / तुमने क्या कहा था). Searches the conversation saved ONLY on this phone (last 30 days + permanent short notes) - no internet. question = the user's question in his own words (include the time like अभी/कल/आज and the topic). Then say the result to the user briefly in your own voice; if it says it was not found, say exactly 'बॉस, मुझे ये याद नहीं मिल रहा।' - never invent.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("question", JSONObject().put("type", "STRING").put("description", "The user's question, his words")))
                .put("required", JSONArray().put("question"))))
        .put(JSONObject().put("name", "local_info")
            .put("description", "Get the user's CURRENT location (fresh GPS/network, checked right now - never an old saved one): street, area, city, nearby places (temple, market, hospital, petrol pump, school...) and live weather. Call it when the user asks where he is (\"मैं अभी कहाँ खड़ा हूँ\", \"मैं कहाँ हूँ\"), what is around him (आसपास), about weather, his area, nearby events/news/traffic/pandals/melas, or anything 'here/मेरे शहर'. While it runs, say only \"रुकिए सर, देखती हूँ...\". Then answer in short human sentences like \"सर, आप अभी ... के पास हैं। आपके आसपास ... है।\" For local news or events, then use Google Search with the city name.")
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
        .put(JSONObject().put("name", "remember_fact")
            .put("description", "Save a personal fact or preference the user told about themselves.")
            .put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("fact", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("fact"))))))
    // v3.72: जुड़े कनेक्टर (Gmail, Drive, GitHub, FB Page, MCP...) - कोई नहीं जुड़ा = कुछ नहीं जुड़ता
    com.dk.assistant.connect.ConnectorTool.declaration(svc)?.let { d ->
        tools.optJSONObject(0)?.optJSONArray("functionDeclarations")?.put(d); log("कनेक्टर: connector_task जोड़ा")
    }
    // v3.93: Tavily/Exa की key हो तो Live की अपनी Google Search नहीं - हर खोज web_search से (Tavily -> Exa -> Gemini क्रम)
    val ownChain = Prefs.tavilySearchKey(svc).isNotBlank() || Prefs.exaSearchKey(svc).isNotBlank()
    if (useSearch && !ownChain) tools.put(JSONObject().put("googleSearch", JSONObject()))
    val setup = JSONObject()
        .put("model", "models/$m")
        .put("generationConfig", JSONObject()
            // v3.68: बँटवारा - Live सिर्फ़ जवाब लिखे (TEXT), बोलना अलग आवाज़ इंजन (voice/engine). मॉडल TEXT न ले तो
            // AUDIO + transcript (Live की आवाज़ बजती नहीं, उसका लिखा text इंजन बोलता है)
            .put("responseModalities", JSONArray().put(if (txt) "TEXT" else "AUDIO"))
            .apply {
                // v3.9: 2.5 native-audio pehle "sochta" hai (der). Baatcheet me band - mushkil sawaal deep_think tool se.
                // (3.1 ka default "minimal" hai; 3.8-live me thinking setting dena mana hai)
                if (thinkOff && m.startsWith("gemini-2.5")) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            .apply { if (ex.affective && !txt && LiveSetupRules.sendAffective(m)) put("enableAffectiveDialog", true) }
            .apply { if (!txt) put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", ex.voice)))
                .apply { if (ex.lang && LiveSetupRules.sendLanguageCode(m)) put("languageCode", LiveVoice.LANGUAGE) }) })
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(brain.liveSystemPrompt(withMemory = false)) + FAST_START + NetSpeed.promptNote() + carryNote(resume) + (if (connSplit) SPLIT_NOTE + (if (txt) MOOD_TAG_NOTE else "") else
            com.dk.assistant.voice.engine.LiveNativeEngine.styleNote(com.dk.assistant.voice.engine.LiveNativeEngine.style(svc).ifBlank { CalmPersona.VOICE_STYLE }))))))
        .put("tools", LiveStay.cleanTools(tools))
        .put("realtimeInputConfig", JSONObject()
            .put("automaticActivityDetection", JSONObject()
                .put("disabled", false)
                // v3.9.1: wapas HIGH. Shor-gate ke baad server ko sirf (a) phone ki pakdi user-awaaz ya (b) khaamoshi (0) jaati hai -
                // shor pehle hi ruk chuka. LOW ne user ki asli (dheemi/chhoti) baat bhi "bolna" nahi maani: log me
                // ~15 baar audioStreamEnd, jawab sirf 3. HIGH = jo phone bheje woh suna jaye.
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                // HIGH = chup hote hi jaldi "bolna khatam" (tez jawab); 500ms = docs ki salaah (500-800ms) ka sabse tez.
                // (server ka default ~800ms tha.) Phone khud bhi 500ms chup par audioStreamEnd bhejta hai (hybrid VAD).
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                // 100ms = pehla shabd na kate (phone 320ms pehle ki awaaz bhi bhejta hai), khatak/thak par "bolna shuru" nahi
                .put("prefixPaddingMs", PREFIX_PADDING_MS)
                .put("silenceDurationMs", SERVER_SILENCE_MS))
            .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS"))
        .put("inputAudioTranscription", JSONObject())
        .apply { if (!txt) put("outputAudioTranscription", JSONObject()) }
        .put("sessionResumption", if (resume && handle != null) JSONObject().put("handle", handle) else JSONObject())
        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
    return JSONObject().put("setup", setup).toString()
}
