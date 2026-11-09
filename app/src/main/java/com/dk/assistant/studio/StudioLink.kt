package com.dk.assistant.studio

import android.content.Context
import com.dk.assistant.TaskStatus

/**
 * नवंबर 2026 (मालिक): "ऐप स्टूडियो सबसे जुड़ा हो - कोडिंग एजेंट, DK कंप्यूटर और DK लाइव को पता हो कि स्टूडियो में
 * क्या बन रहा है, कौन-सा कदम कहाँ चल रहा है".
 *
 * - [KEY] = [TaskStatus] में स्टूडियो का काम (DK लाइव का task_status, notification, काम का इतिहास, खत्म होने पर SYSTEM NOTE).
 * - [snapshot] = स्टूडियो की असली हालत (project, किसने बनाया, अभी का कदम, फ़ाइलें, आख़िरी नतीजा) - सिर्फ़ जो सच में दर्ज है.
 * - [parse] / [handle] = कोडिंग एजेंट और DK कंप्यूटर की स्क्रीन पर "स्टूडियो में क्या बन रहा है / बदलो / रोको / खोलो".
 * - [liveTool] = DK लाइव का app_studio tool.
 */
object StudioLink {
    const val KEY = "studio"
    const val WHO = "ऐप स्टूडियो"
    const val WHO_EN = "App Studio"

    enum class Act { STATUS, OPEN, STOP, CHANGE, BUILD }
    data class Cmd(val act: Act, val text: String)

    // "Android Studio" / "YouTube Studio" / "Creator Studio" = दूसरी चीज़ें, ऐप स्टूडियो नहीं
    private val OTHER = Regex("(?<![\\p{L}\\p{M}])(android|एंड्रॉइड|एंड्रॉयड|एंड्राइड|youtube|यूट्यूब|creator|क्रिएटर|visual|विज़ुअल|विजुअल|ai|music|मीटिंग|photo|फोटो|फ़ोटो|recording|रिकॉर्डिंग)\\s*(studio|स्टूडियो)")
    private val NAME = Regex("(?<![a-z])studio(?![a-z])|स्टूडियो|स्टुडियो|स्टूडियों")
    private val STOP_W = listOf("रोको", "रोक दो", "रोक दीजिए", "बंद करो", "बंद कर दो", "stop", "cancel", "रद्द")
    private val ASK_W = listOf("क्या", "कहाँ", "कहां", "कितना", "कितनी", "कैसा", "हालत", "स्टेटस", "status", "progress", "प्रोग्रेस", "चल रहा", "चल रही",
        "बन रहा", "बन रही", "हुआ", "हो गया", "बना क्या", "बताओ", "बता", "what", "how", "which", "कौन")
    private val BUILD_W = listOf("बनाओ", "बना दो", "बना दीजिए", "बनाना है", "build", "make", "create", "नया ऐप", "new app")
    private val CHANGE_W = listOf("बदलो", "बदल दो", "बदलना", "जोड़ो", "जोड़ दो", "जोड़ना", "हटाओ", "हटा दो", "ठीक करो", "ठीक कर दो", "सुधारो", "कर दो", "करो",
        "change", "add", "remove", "fix", "update", "बढ़ाओ", "घटाओ", "लगाओ", "लगा दो")
    private val OPEN_W = listOf("खोलो", "खोल दो", "दिखाओ", "दिखा दो", "open", "show")

    /** यह बात ऐप स्टूडियो के बारे में है? null = नहीं (बाकी सब पहले जैसा) */
    fun parse(raw: String?): Cmd? {
        val t0 = raw?.trim().orEmpty()
        if (t0.length < 4) return null
        val low = " " + t0.lowercase().replace(Regex("\\s+"), " ") + " "
        val cleaned = OTHER.replace(low, " ")
        if (!NAME.containsMatchIn(cleaned)) return null
        val has = { w: List<String> -> w.any { k -> if (k.all { it in 'a'..'z' || it == ' ' }) Regex("(?<![a-z])${Regex.escape(k)}(?![a-z])").containsMatchIn(cleaned) else cleaned.contains(k) } }
        val question = t0.endsWith("?") || has(ASK_W)
        return when {
            has(STOP_W) -> Cmd(Act.STOP, t0)
            question -> Cmd(Act.STATUS, t0)
            has(BUILD_W) && !Regex("(वाले|वाली|वाला|के|की) (ऐप|app)").containsMatchIn(cleaned) -> Cmd(Act.BUILD, StudioCore.extract(t0) ?: t0)
            has(CHANGE_W) || has(BUILD_W) -> Cmd(Act.CHANGE, t0)
            has(OPEN_W) -> Cmd(Act.OPEN, t0)
            else -> Cmd(Act.STATUS, t0)
        }
    }

    private fun whoHi(w: String) = if (w == StudioSession.WHO_CODER) "कोडिंग एजेंट" else "DK कंप्यूटर"

    /** स्टूडियो की असली हालत (हिंदी) - कोडिंग एजेंट / DK कंप्यूटर स्क्रीन और DK लाइव दोनों के लिए */
    fun snapshot(c: Context?): String {
        val p = StudioSession.project ?: c?.let { try { StudioProject.all(it).firstOrNull() } catch (_: Exception) { null } }
        val steps = StudioSession.stepsNow()
        val job = TaskStatus.get(KEY)
        return text(
            title = p?.title, kind = p?.kind?.hi, who = p?.who?.let { whoHi(it) }, busy = StudioSession.busy,
            steps = steps, files = try { p?.files().orEmpty() } catch (_: Exception) { emptyList() },
            preview = try { p?.hasPreview() == true } catch (_: Exception) { false },
            last = StudioSession.lastResult.ifBlank { job?.result.orEmpty() },
            recent = c?.let { ctx -> try { StudioProject.all(ctx).take(4).map { it.title } } catch (_: Exception) { emptyList() } }.orEmpty(),
            jobLine = job?.let { TaskStatus.line(it) },
        )
    }

    /** शुद्ध हिस्सा (JVM test) */
    internal fun text(title: String?, kind: String?, who: String?, busy: Boolean, steps: List<String>, files: List<String>, preview: Boolean,
                      last: String, recent: List<String>, jobLine: String?): String = buildString {
        if (title == null) {
            append("ऐप स्टूडियो: अभी कोई project नहीं बना। \"एक todo ऐप बनाओ\" जैसा बोलें - DK कंप्यूटर / कोडिंग एजेंट स्टूडियो में बनाएगा।")
            return@buildString
        }
        appendLine("ऐप स्टूडियो - project: \"$title\"" + (kind?.let { " ($it)" } ?: "") + (who?.let { " • किसने: $it" } ?: ""))
        appendLine("हालत: " + if (busy) "अभी काम चल रहा है" + (jobLine?.let { " (${it.lineSequence().first()})" } ?: "") else "अभी कोई काम नहीं चल रहा")
        if (steps.isNotEmpty()) {
            appendLine((if (busy) "अभी का कदम: " else "आख़िरी कदम: ") + steps.last())
            val prev = steps.dropLast(1).takeLast(5)
            if (prev.isNotEmpty()) appendLine("पिछले कदम: " + prev.joinToString(" → "))
        }
        appendLine("फ़ाइलें (${files.size}): " + (if (files.isEmpty()) "अभी कोई नहीं" else files.take(12).joinToString(", ") + if (files.size > 12) " …" else ""))
        appendLine("preview: " + if (preview) "तैयार (स्टूडियो में चलता है)" else "अभी नहीं बना")
        if (!busy && last.isNotBlank()) appendLine("आख़िरी नतीजा: " + last.lineSequence().first().take(220))
        val others = recent.filter { it != title }.take(3)
        if (others.isNotEmpty()) append("दूसरे project: " + others.joinToString(", "))
    }.trim()

    /** कोडिंग एजेंट / DK कंप्यूटर की स्क्रीन: स्टूडियो वाली बात हो तो जवाब (Pair: जवाब, ठीक?) वरना null */
    fun handle(c: Context, raw: String, who: String): Pair<String, Boolean>? {
        val cmd = parse(raw) ?: return null
        return when (cmd.act) {
            Act.STATUS -> snapshot(c) to true
            Act.OPEN -> { StudioSession.open(c, null); "🧩 ऐप स्टूडियो खोल दिया।\n" + snapshot(c) to true }
            Act.STOP -> if (StudioSession.busy) { StudioSession.stop(); "⏹ ऐप स्टूडियो का काम रोक दिया।" to true } else "ऐप स्टूडियो में अभी कोई काम नहीं चल रहा।" to true
            Act.CHANGE -> StudioSession.change(c, cmd.text)?.let { it to false }
                ?: ("✏ ऐप स्टूडियो में बदलाव शुरू: \"${cmd.text.take(80)}\" - स्टूडियो की स्क्रीन पर बनते हुए दिखेगा।".also { StudioSession.open(c, null) } to true)
            Act.BUILD -> StudioSession.startFrom(c, cmd.text, who)?.let { it to false }
                ?: ((if (who == StudioSession.WHO_CODER) StudioRoute.STARTED_CODER else StudioRoute.STARTED_COMPUTER) to true)
        }
    }

    /** DK लाइव: app_studio(action, text) */
    fun liveTool(c: Context, action: String, text: String, who: String = StudioSession.WHO_COMPUTER): String {
        val a = action.trim().lowercase()
        return when (a) {
            "status", "" -> "APP STUDIO REAL STATUS (tell the user only this, briefly, in his language; never invent progress):\n" + snapshot(c)
            "open" -> { StudioSession.open(c, null); "OPENED App Studio on the phone screen. Current state:\n" + snapshot(c) }
            "stop" -> if (StudioSession.busy) { StudioSession.stop(); "STOPPED the App Studio work." } else "NOTHING RUNNING in App Studio - nothing to stop."
            "change" -> if (text.isBlank()) "Ask the user what to change in the App Studio app." else
                StudioSession.change(c, text)?.let { "NOT STARTED: $it" }
                    ?: run { StudioSession.open(c, null); "Change STARTED in App Studio (NOT done yet). The studio screen shows each file; you get a SYSTEM NOTE when it really ends. Real progress: app_studio action=status." }
            "build" -> {
                val want = StudioCore.extract(text) ?: text.trim()
                if (want.length < 3) "Ask the user what app to build."
                else StudioSession.startFrom(c, want, who, voice = true)?.let { "NOT STARTED: $it" }
                    ?: "STARTED building in App Studio (NOT done yet): ${StudioRoute.STARTED_VOICE} You get a SYSTEM NOTE when it really ends."
            }
            else -> "Unknown action - use status, open, change, stop or build."
        }
    }

    /**
     * DK लाइव का computer_task / coding_task: स्टूडियो वाली बात (हालत / बदलाव / रोको / खोलो) = स्टूडियो से जवाब;
     * नया "ऐप बनाओ" = स्टूडियो में (DK कंप्यूटर हमेशा, कोडिंग एजेंट तब जब GitHub नहीं जुड़ा - स्क्रीन वाले रास्ते जैसा).
     * null = स्टूडियो की बात नहीं, पुराना रास्ता.
     */
    fun fromLive(c: Context, t: String, who: String, noGithub: Boolean): String? {
        parse(t)?.let { cmd ->
            return when (cmd.act) {
                Act.STATUS -> liveTool(c, "status", "")
                Act.OPEN -> liveTool(c, "open", "")
                Act.STOP -> liveTool(c, "stop", "")
                Act.CHANGE -> liveTool(c, "change", cmd.text)
                Act.BUILD -> liveTool(c, "build", cmd.text, who)
            }
        }
        if (who == StudioSession.WHO_CODER && !noGithub) return null
        val want = StudioRoute.voiceApp(t) ?: return null
        return liveTool(c, "build", want, who)
    }

    /** काम खत्म = DK लाइव को सच (TaskStatus SYSTEM NOTE की जगह, स्टूडियो के लिए) */
    fun liveNote(j: TaskStatus.Job): String? {
        if (j.key != KEY) return null
        val ok = j.state == TaskStatus.State.DONE
        return "SYSTEM NOTE: App Studio work (\"${j.task}\") has really ended - state: ${j.state.en}. Result: \"${j.result.ifBlank { "-" }}\". " +
            (if (ok) "Tell the user briefly in his language: आपका ऐप तैयार है, स्टूडियो में चल रहा है - चेक कर लीजिए, और जो बदलना हो बोल दीजिए। "
            else "Tell the user briefly and honestly that it did not finish, and why (from the result). Do NOT say it is ready. ") +
            "Do not invent details."
    }
}
