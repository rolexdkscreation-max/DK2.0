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

    fun chatLabel(n: Int) = "Chat #${n.coerceAtLeast(1)}"

    /** आवाज़ वाले टैब बदले -> Live को SYSTEM NOTE (जो टूल असल में हैं उन्हीं के नाम) */
    fun voiceModeNote(m: DkaiMode): String = "SYSTEM NOTE (do not read this aloud): the user switched the DKAI voice screen to the " +
        m.label.uppercase() + " tab. " + when (m) {
            DkaiMode.CHAT -> "Just talk normally and answer questions."
            DkaiMode.BUILDER -> "From now on, when the user asks you to make or change an app, use the app_studio tool (build_website for a website)."
            DkaiMode.AGENT -> "From now on, when the user asks for a multi-step task, use the computer_task tool."
            DkaiMode.PIPELINE -> "From now on, when the user asks for code changes in their GitHub repository, use the coding_task tool."
            DkaiMode.BROADCAST -> "Screen sharing is being turned on so you can see the user's screen."
        } + " Confirm in one very short sentence in the user's language."

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
