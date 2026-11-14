package com.dk.assistant.dkai

import com.dk.assistant.Mode
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * नवंबर 2026 (मालिक): DKAI - 3 हालत वाली AI आवाज़ + IDE स्क्रीन.
 * यह फ़ाइल pure logic है (Android View नहीं) -> JVM test से जाँची जाती है.
 *
 * voiceSessionState:
 *   'inactive'          = होम / चैट (State 1)
 *   'active_full'       = पूरी स्क्रीन वाला आवाज़ orb (State 2)
 *   'active_minimized'  = नीचे छोटी तैरती पट्टी, होम पीछे पूरा चलता रहे (State 3)
 */
enum class VoiceSessionState(val id: String) {
    INACTIVE("inactive"), ACTIVE_FULL("active_full"), ACTIVE_MINIMIZED("active_minimized");
    val active get() = this != INACTIVE
}

enum class VoiceEvent {
    /** होम का माइक बटन */ MIC_TAP,
    /** (v) बटन */ MINIMIZE,
    /** (^) बटन / छोटी पट्टी पर टैप */ EXPAND,
    /** लाल (X) - बातचीत खत्म */ CLOSE,
    /** Live बाहर से बंद हुआ (notification का "कॉल काटें", "बंद करो" बोला, गलती) */ LIVE_ENDED,
    /** Live अपने-आप चालू हुआ (ऐप खुलते ही) - माइक खुला है तो छोटी पट्टी दिखे, छुपा नहीं */ LIVE_STARTED,
    /** फ़ोन का back */ BACK,
}

/** कौन-सा काम (होम के 4 कार्ड + आवाज़ के 5 टैब) */
enum class DkaiMode(val label: String) {
    CHAT("Chat"), BUILDER("Builder"), AGENT("Agent"), PIPELINE("Pipeline"), BROADCAST("Broadcast");
    companion object {
        val CARDS = listOf(CHAT, BUILDER, AGENT, PIPELINE)
        val VOICE_TABS = listOf(CHAT, BUILDER, AGENT, PIPELINE, BROADCAST)
    }
}

/** नीचे की 6 टैब */
enum class DkaiTab(val label: String) { HOME("Home"), FILES("Files"), EDITOR("Editor"), TERMINAL("Terminal"), PREVIEW("Preview"), GITHUB("GitHub") }

object DkaiCore {

    // ------------------------------------------------------------------ होम का लिखने वाला डिब्बा
    /**
     * 14 नवंबर 2026 (मालिक): मोड वाली गोली ("Chat ▾" … "Broadcast ▾") की पक्की चौड़ाई (dp). सबसे लंबा नाम भी इसमें आए,
     * और कोई भी मोड चुनें - माइक / भेजें बटन अपनी जगह से न हिलें, न छिपें.
     */
    const val MODE_PILL_DP = 100f
    /** पंक्ति के पक्के हिस्से (dp): + (40) + मोड गोली + उसके दोनों ओर 6+6 + भेजें (42 + 4) + माइक (48) */
    const val INPUT_ROW_FIXED_DP = 40f + MODE_PILL_DP + 12f + 46f + 48f

    /** इस चौड़ाई (dp) के फ़ोन पर पंक्ति के सारे पक्के बटन दिखेंगे? (डिब्बे के margin 12+12, padding 14+8) */
    fun inputRowFits(screenDp: Float): Boolean = screenDp - 24f - 22f >= INPUT_ROW_FIXED_DP

    // ------------------------------------------------------------------ हालत बदलना
    fun next(s: VoiceSessionState, e: VoiceEvent): VoiceSessionState = when (e) {
        VoiceEvent.MIC_TAP -> VoiceSessionState.ACTIVE_FULL
        VoiceEvent.MINIMIZE -> if (s == VoiceSessionState.ACTIVE_FULL) VoiceSessionState.ACTIVE_MINIMIZED else s
        VoiceEvent.EXPAND -> if (s == VoiceSessionState.ACTIVE_MINIMIZED) VoiceSessionState.ACTIVE_FULL else s
        VoiceEvent.CLOSE, VoiceEvent.LIVE_ENDED -> VoiceSessionState.INACTIVE
        VoiceEvent.LIVE_STARTED -> if (s == VoiceSessionState.INACTIVE) VoiceSessionState.ACTIVE_MINIMIZED else s
        // back: पूरी स्क्रीन = छोटी करो (बातचीत चलती रहे); बाकी में back हमारा नहीं
        VoiceEvent.BACK -> if (s == VoiceSessionState.ACTIVE_FULL) VoiceSessionState.ACTIVE_MINIMIZED else s
    }

    /** back हमारा है? (पूरी स्क्रीन वाला orb खुला) */
    fun handlesBack(s: VoiceSessionState) = s == VoiceSessionState.ACTIVE_FULL

    /** Live इतनी देर लगातार बंद रहे तब बातचीत खत्म मानो (दोबारा जुड़ते पल की झपक पर नहीं) */
    const val LIVE_END_GRACE_MS = 1_500L

    // ------------------------------------------------------------------ आवाज़ें
    /** [name] = स्क्रीन पर नाम, [api] = Gemini Live की असली आवाज़, [gender] = "female"/"male" */
    data class Voice(val name: String, val api: String, val gender: String) { val female get() = gender == "female" }

    val VOICES = listOf(
        Voice("Emma", "Sulafat", "female"),     // DK की अपनी डिफ़ॉल्ट आवाज़
        Voice("Swara", "Kore", "female"),
        Voice("Ananya", "Leda", "female"),
        Voice("Aarav", "Puck", "male"),
        Voice("Rohan", "Charon", "male"),
    )

    /** सेव आवाज़ -> सूची का नाम (सूची में न हो = Emma, क्योंकि DK उस हालत में Sulafat बोलता है) */
    fun voiceFor(api: String?, gender: String?): Voice =
        VOICES.firstOrNull { it.api.equals(api, true) && (gender == null || it.gender == gender) }
            ?: VOICES.firstOrNull { it.api.equals(api, true) }
            ?: if (gender == "male") VOICES.first { !it.female } else VOICES.first()

    // ------------------------------------------------------------------ गति
    val SPEEDS = listOf(100, 120, 130, 150, 200)
    fun speedLabel(pct: Int): String = String.format(Locale.US, "%.1fx", pct / 100f)
    /** अभी की गति सूची में न हो (Settings में 115% चुना) तो सबसे पास वाली पर निशान */
    fun nearestSpeed(pct: Int): Int = SPEEDS.minByOrNull { kotlin.math.abs(it - pct) } ?: 100

    // ------------------------------------------------------------------ ऊपर का बैज
    enum class Status(val label: String) { READY("READY"), CONNECTING("CONNECTING"), LISTENING("LISTENING"), THINKING("THINKING"), SPEAKING("SPEAKING"), MUTED("MUTED"), SETUP("SETUP"), OFFLINE("OFFLINE") }

    /** आवाज़ वाली स्क्रीन का बैज - Live की असली हालत से */
    fun voiceStatus(mode: Mode, live: Boolean, muted: Boolean, work: Boolean, connected: Boolean): Status = when {
        mode == Mode.SPEAKING -> Status.SPEAKING
        mode == Mode.WORKING -> Status.THINKING
        mode == Mode.THINKING && (work || !live) -> Status.THINKING
        !live -> Status.OFFLINE
        !connected -> Status.CONNECTING
        muted -> Status.MUTED
        else -> Status.LISTENING
    }

    /**
     * 13 नवंबर 2026 (मालिक - वॉइस-कॉल स्क्रीन): कॉल पर सिर्फ़ तीन हालत - LISTENING / PROCESSING / SPEAKING.
     * [voiceStatus] वैसा ही रहता है; यहाँ उसे तीन में समेटा: सोच / जुड़ रहा = PROCESSING, बाकी (म्यूट भी) = LISTENING.
     */
    enum class Call(val label: String, val title: String, val hint: String) {
        LISTENING("LISTENING", "Listening…", "Tap to stop"),
        PROCESSING("PROCESSING", "Processing…", "One moment…"),
        SPEAKING("SPEAKING", "Speaking…", "Here's what I found for you")
    }
    /** 14 नवंबर 2026 (मालिक): Live जुड़ते समय "Processing…" नहीं, "Connecting…" */
    const val CONNECTING_TITLE = "Connecting…"
    const val CONNECTING_HINT = "DK Live से जुड़ रहा है…"
    fun callBadge(st: Status): String = if (st == Status.CONNECTING) Status.CONNECTING.label else callState(st).label
    fun connectingOr(st: Status, ct: CallText): CallText = if (st == Status.CONNECTING) CallText(CONNECTING_TITLE, CONNECTING_HINT, true) else ct
    const val MUTED_LINE = "Microphone is muted."
    const val MUTED_HINT = "Tap the mic to talk again"

    fun callState(st: Status): Call = when (st) {
        Status.SPEAKING -> Call.SPEAKING
        Status.THINKING, Status.CONNECTING -> Call.PROCESSING
        else -> Call.LISTENING
    }

    /** टेक्स्ट बॉक्स: (ऊपर की लाइन, बात, बात असली है या सिर्फ़ इशारा) */
    data class CallText(val title: String, val body: String, val placeholder: Boolean)

    /**
     * एक समय पर सिर्फ़ वही जो अभी बोल रहा है: सुनते समय यूज़र की लाइव बात (सिर्फ़ वही जो DK के आख़िरी जवाब के बाद बोली),
     * बोलते समय DK का जवाब, सोचते समय बॉक्स साफ़. म्यूट (सुनने की हालत में) = "Microphone is muted."
     */
    fun callText(call: Call, muted: Boolean, user: String, userAt: Long, reply: String, lastDk: String, lastDkAt: Long, sessionStart: Long): CallText {
        return when (call) {
            Call.SPEAKING -> {
                val r = reply.ifBlank { if (lastDkAt >= sessionStart && lastDkAt >= userAt) lastDk else "" }
                if (r.isNotBlank()) CallText(call.title, tail(r), false) else CallText(call.title, call.hint, true)
            }
            Call.PROCESSING -> CallText(call.title, call.hint, true)
            Call.LISTENING -> {
                if (muted) return CallText(MUTED_LINE, MUTED_HINT, true)
                val u = user.trim().removeSuffix("…").trim()
                if (u.isNotBlank() && userAt >= sessionStart && userAt > lastDkAt) CallText(call.title, tail(u), false)
                else CallText(call.title, call.hint, true)
            }
        }
    }

    /** होम का बैज: कुछ न चले = READY (key नहीं = SETUP) */
    fun homeStatus(hasKey: Boolean, mode: Mode, live: Boolean, work: Boolean): Status = when {
        !hasKey -> Status.SETUP
        mode == Mode.SPEAKING -> Status.SPEAKING
        mode == Mode.WORKING || (mode == Mode.THINKING && (work || !live)) -> Status.THINKING
        else -> Status.READY
    }

    // ------------------------------------------------------------------ मॉडल वाली पिल
    /** "Gemini" + "gemini-3.5-flash-lite" -> "Gemini Flash-Lite"; "gemini-3.1-pro-preview" -> "Gemini Pro" */
    fun modelPill(provider: String, model: String): String {
        val m = model.lowercase().substringAfterLast('/')
        val fam = when {
            m.contains("flash-lite") || m.contains("flash_lite") -> "Flash-Lite"
            m.contains("flash") -> "Flash"
            m.contains("pro") -> "Pro"
            m.contains("mini") -> "Mini"
            m.contains("nano") -> "Nano"
            else -> null
        }
        val p = provider.trim().ifBlank { "AI" }.substringBefore(' ').let { if (it.equals("google", true)) "Gemini" else it }
        return if (fam != null) "$p $fam" else "$p ${model.substringAfterLast('/').take(18)}".trim()
    }

    // ------------------------------------------------------------------ लिखावट
    /** छोटी पट्टी के लिए: "Hello, how can I help you today?" -> "Hello, how can I..." */
    fun preview(text: String, max: Int = 18): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.substring(0, max).let { s -> s.substringBeforeLast(' ', s).trimEnd(',', '.', ' ') }
        return "$cut..."
    }

    /** लंबा जवाब = आख़िरी हिस्सा (orb के नीचे 3 लाइन में) */
    fun tail(text: String, max: Int = 160): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val s = t.substring(t.length - max)
        return "…" + s.substringAfter(' ', s)
    }

    const val HELLO = "Hello, how can I help you today?"

    /** 14 नवंबर 2026 (मालिक): "Chat #1" का # अच्छा नहीं दिखता - सिर्फ़ "Chat 1" */
    fun chatLabel(n: Int) = "Chat ${n.coerceAtLeast(1)}"

    /** आवाज़ वाले टैब बदले -> Live को SYSTEM NOTE (जो टूल असल में हैं उन्हीं के नाम) */
    fun voiceModeNote(m: DkaiMode): String = "SYSTEM NOTE (do not read this aloud): the user switched the DKAI voice screen to the " +
        m.label.uppercase() + " tab. Everything he asks now belongs to this tab. " + when (m) {
            DkaiMode.CHAT -> "Just talk normally and answer questions; a story he asks for is written into the chat (story_task)."
            DkaiMode.BUILDER -> "From now on, when the user asks you to make or change an app, use the app_studio tool (build_website for a website) - it is built in the Builder."
            DkaiMode.AGENT -> "From now on, when the user asks for a multi-step task, use the computer_task tool - it runs in the Agent (DK Computer)."
            DkaiMode.PIPELINE -> "From now on, when the user asks for code, an app, a website or a game, use the coding_task tool - it is built step by step in the Pipeline."
            DkaiMode.BROADCAST -> "From now on, when the user asks for a story, podcast, news bulletin, speech or lecture, call story_task with his topic: " +
                "the script is written live on his screen, every character gets its own voice and tone, and the audio is made and played automatically. Do not tell the story yourself."
        } + " Confirm in one very short sentence in the user's language."

    /** 14 नवंबर 2026: DK लाइव का tool किस मोड में कहाँ जाए */
    sealed class Route {
        /** इसी मोड में चलाओ: cast / pipeline / builder / agent / chat */
        data class Run(val target: String) : Route()
        /** यह काम इस मोड का नहीं - मालिक से [suggest] टैब पर जाने को कहो */
        data class Ask(val suggest: DkaiMode) : Route()
        /** मोड से कोई लेना-देना नहीं - पुराना रास्ता */
        object Pass : Route()
    }

    val BUILD_TOOLS = setOf("coding_task", "build_website", "make_game", "computer_task")

    /**
     * मालिक: "बोर्ड कास्ट सेलेक्ट किया हूँ तो उसी के ऊपर काम… पाइपलाइन तो पाइपलाइन… चैट तो चैट… बिल्डर… एजेंट".
     * [mode] = DKAI आवाज़ वाली स्क्रीन का चुना टैब (null = DKAI से बाहर का DK लाइव = पुराना रास्ता).
     */
    fun routeFor(mode: DkaiMode?, tool: String): Route {
        if (mode == null) return Route.Pass
        val story = tool == "story_task"; val build = tool in BUILD_TOOLS
        if (!story && !build) return Route.Pass
        return when (mode) {
            DkaiMode.BROADCAST -> if (story) Route.Run("cast") else Route.Ask(if (tool == "computer_task") DkaiMode.AGENT else DkaiMode.PIPELINE)
            DkaiMode.PIPELINE -> if (build && tool != "computer_task") Route.Run("pipeline") else Route.Ask(if (story) DkaiMode.BROADCAST else DkaiMode.AGENT)
            DkaiMode.BUILDER -> if (build && tool != "computer_task") Route.Run("builder") else Route.Ask(if (story) DkaiMode.BROADCAST else DkaiMode.AGENT)
            DkaiMode.AGENT -> Route.Run("agent")
            DkaiMode.CHAT -> if (story) Route.Run("chat") else Route.Ask(if (tool == "computer_task") DkaiMode.AGENT else DkaiMode.PIPELINE)
        }
    }

    fun routeAskNote(cur: DkaiMode, suggest: DkaiMode): String =
        "NOT_STARTED: the user has the ${cur.label.uppercase()} tab selected and this job belongs to the ${suggest.label.uppercase()} tab. " +
            "In one short sentence in his language tell him to tap the ${suggest.label} tab at the top (or say it) and then ask again. Do not start it here."

    // ------------------------------------------------------------------ फ़ाइलें (रास्ता हमेशा अपने फ़ोल्डर के अंदर)
    fun inside(root: File, f: File): Boolean = try {
        val r = root.canonicalPath; val p = f.canonicalPath
        p == r || p.startsWith(r + File.separator)
    } catch (_: Exception) { false }

    /** नया नाम ठीक है? (खाली, "/", "..", छुपे control अक्षर नहीं) */
    fun validName(n: String): Boolean {
        val t = n.trim()
        return t.isNotEmpty() && t.length <= 120 && t != "." && t != ".." && !t.contains('/') && !t.contains('\\') && t.none { it.code < 32 }
    }

    private val TEXT_EXT = setOf("txt", "md", "kt", "kts", "java", "js", "mjs", "ts", "tsx", "jsx", "json", "html", "htm", "css", "scss", "xml",
        "yml", "yaml", "py", "sh", "gradle", "properties", "toml", "ini", "cfg", "c", "h", "cpp", "hpp", "go", "rs", "rb", "php", "sql",
        "csv", "tsv", "svg", "gitignore", "env", "log", "swift", "dart", "vue", "lock", "conf", "bat")
    fun isTextName(n: String): Boolean {
        val l = n.lowercase(); val ext = l.substringAfterLast('.', "")
        return ext in TEXT_EXT || (!l.contains('.')) || l.startsWith(".")
    }
    fun isHtml(n: String) = n.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }

    /** bytes text हैं? (शुरू में 0 वाला byte नहीं) */
    fun looksText(b: ByteArray): Boolean = b.take(4000).none { it == 0.toByte() }

    // ------------------------------------------------------------------ टर्मिनल
    /** "cd x" / "cd" / "cd .." -> नया फ़ोल्डर (Workspace से बाहर नहीं); null = यह cd नहीं */
    fun cd(cmd: String, cwd: File, root: File): File? {
        val t = cmd.trim()
        if (t != "cd" && !t.startsWith("cd ")) return null
        val arg = t.removePrefix("cd").trim().trim('"', '\'')
        val target = when {
            arg.isEmpty() || arg == "~" -> root
            arg.startsWith("~/") -> File(root, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(arg)
            else -> File(cwd, arg)
        }
        return try { target.canonicalFile } catch (_: Exception) { target }
    }

    /** टर्मिनल में दिखने वाला रास्ता: root = "~" */
    fun prompt(cwd: File, root: File): String {
        val r = try { root.canonicalPath } catch (_: Exception) { root.path }
        val p = try { cwd.canonicalPath } catch (_: Exception) { cwd.path }
        return if (p == r) "~" else if (p.startsWith("$r/")) "~/" + p.removePrefix("$r/") else p
    }

    /** इतिहास में ऊपर/नीचे ([dir] -1 = पुराना) */
    fun historyAt(h: List<String>, idx: Int, dir: Int): Int = (idx + dir).coerceIn(0, h.size)

    // ------------------------------------------------------------------ GitHub (Workspace में लाया रिपो)
    fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * लाने के समय की फ़ाइलें ([base] path -> sha) और अभी की ([now] path -> content) ->
     * commit के बदलाव: बदली/नई = content, हटाई = null. कुछ न बदला = खाली.
     */
    fun changes(base: Map<String, String>, now: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for ((p, c) in now) if (base[p] != sha(c)) out[p] = c
        for (p in base.keys) if (p !in now) out[p] = null
        return out
    }

    fun branchName(nowMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "dkai/" + f.format(java.util.Date(nowMs))
    }

    const val REPO_META = ".dkai_repo.json"
}
