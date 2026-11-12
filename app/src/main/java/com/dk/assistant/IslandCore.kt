package com.dk.assistant

/**
 * मालिक (12 नवंबर 2026): "DK का कोई भी काम चले और मैं DK से बाहर हूँ, तो iPhone के notch (Dynamic Island) जैसा ऊपर दिखे.
 * टैप करो तो अंदर जो काम चल रहा है - हाल के 1-5 काम - सब दिखें. कॉल आए तो कॉल जैसा, गाना बजे (किसी भी ऐप / YouTube) तो वह भी."
 *
 * यह फ़ाइल सिर्फ़ नियम है (Android import नहीं -> JVM test): island दिखे या नहीं, छोटे island में सबसे ऊपर क्या, बड़े में कौन-से काम.
 * सब कुछ असली हालत से ([TaskStatus], [TaskHistory], कॉल, मीडिया सेशन) - कोई नकली % नहीं.
 */
object IslandCore {

    enum class Kind { CALL, LIVE, TASK, MEDIA, LOCKED }

    data class Call(val who: String, val ringing: Boolean, val since: Long, val app: String = "")
    data class Live(val mode: Mode, val muted: Boolean)
    data class Task(
        val id: String, val key: String, val who: String, val task: String, val state: String,
        val last: String, val steps: Int, val started: Long, val ended: Long, val active: Boolean,
        val ok: Boolean = false,
    )
    data class Media(val title: String, val artist: String, val app: String, val pkg: String, val playing: Boolean)
    /** प्राइवेसी स्क्रीन ने छिपाया notification - island में सिर्फ़ "🔒 ऐप • N नए" (अंदर की बात नहीं) */
    data class Locked(val key: String, val pkg: String, val app: String, val count: Int, val at: Long)

    data class Snap(
        val call: Call? = null, val live: Live? = null, val tasks: List<Task> = emptyList(),
        val media: Media? = null, val locked: List<Locked> = emptyList(),
    )

    /** बड़े island में ज़्यादा से ज़्यादा इतने हाल के काम (मालिक: "1, 2, 3, 4, 5") */
    const val MAX_TASKS = 5
    /** काम खत्म होने के बाद इतनी देर छोटा island "✓ पूरा" दिखाए */
    const val DONE_SHOW_MS = 8_000L
    /** इतनी देर कोई छुए नहीं तो बड़ा island अपने-आप छोटा */
    const val AUTO_COLLAPSE_MS = 9_000L

    /** island दिखे? Settings में चालू + DK ऐप सामने नहीं + कुछ दिखाने लायक */
    fun wanted(prefOn: Boolean, inApp: Boolean, s: Snap, now: Long): Boolean = prefOn && !inApp && lead(s, now) != null

    /** छोटे island में सबसे ऊपर क्या: कॉल > DK लाइव > चलता काम > बजता गाना > अभी खत्म हुआ काम > छिपे notification */
    fun lead(s: Snap, now: Long): Kind? = when {
        s.call != null -> Kind.CALL
        s.live != null -> Kind.LIVE
        s.tasks.any { it.active } -> Kind.TASK
        s.media?.playing == true -> Kind.MEDIA
        s.tasks.any { justEnded(it, now) } -> Kind.TASK
        s.locked.isNotEmpty() -> Kind.LOCKED
        else -> null
    }

    fun justEnded(t: Task, now: Long) = !t.active && t.ended > 0 && now - t.ended in 0..DONE_SHOW_MS

    /** सबसे ऊपर वाले के अलावा कितनी और चीज़ें (छोटे island के दाएँ "+2") */
    fun others(s: Snap, now: Long): Int {
        val l = lead(s, now) ?: return 0
        var n = 0
        if (s.call != null && l != Kind.CALL) n++
        if (s.live != null && l != Kind.LIVE) n++
        n += s.tasks.count { it.active }.let { if (l == Kind.TASK && it > 0) it - 1 else it }
        if (s.media?.playing == true && l != Kind.MEDIA) n++
        if (s.locked.isNotEmpty() && l != Kind.LOCKED) n++
        return n
    }

    /** हाल के काम: पहले चलते, फिर नए से पुराने; एक काम एक बार (id = key:started) */
    fun recent(active: List<Task>, history: List<Task>, max: Int = MAX_TASKS): List<Task> {
        val seen = HashSet<String>()
        val all = (active.sortedByDescending { it.started } + history.sortedByDescending { maxOf(it.ended, it.started) })
        return all.filter { seen.add(it.id) }.sortedWith(compareByDescending<Task> { it.active }.thenByDescending { maxOf(it.ended, it.started) }).take(max)
    }

    fun modeHi(m: Mode): String = when (m) {
        Mode.LISTENING -> "सुन रहा हूँ"
        Mode.THINKING -> "सोच रहा हूँ"
        Mode.WORKING -> "फ़ोन पर काम कर रहा हूँ"
        Mode.SPEAKING -> "बोल रहा हूँ"
        Mode.IDLE -> "लाइव चालू है"
    }

    /** 65 सेकंड -> "1:05" */
    fun timer(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}:${"%02d".format((s / 60) % 60)}:${"%02d".format(s % 60)}" else "${s / 60}:${"%02d".format(s % 60)}"
    }

    /** छोटे island की बाईं ओर की लाइन (छोटी) */
    fun compactText(s: Snap, now: Long): String = when (lead(s, now)) {
        Kind.CALL -> s.call!!.let { (if (it.ringing) "कॉल: " else "") + it.who.ifBlank { "अनजान नंबर" } }
        Kind.LIVE -> s.live!!.let { if (it.muted) "म्यूट" else modeHi(it.mode) }
        Kind.TASK -> (s.tasks.firstOrNull { it.active } ?: s.tasks.first { justEnded(it, now) }).let { t ->
            if (t.active) t.who else (if (t.ok) "✓ " else "• ") + t.who
        }
        Kind.MEDIA -> s.media!!.title.ifBlank { s.media.app }
        Kind.LOCKED -> "🔒 ${s.locked.sumOf { it.count }} छिपे"
        null -> ""
    }

    /** छोटे island की दाईं ओर (समय / कदम / हालत) */
    fun compactRight(s: Snap, now: Long): String = when (lead(s, now)) {
        Kind.CALL -> s.call!!.let { if (it.ringing) "बज रहा" else timer(now - it.since) }
        Kind.LIVE -> "LIVE"
        Kind.TASK -> (s.tasks.firstOrNull { it.active } ?: s.tasks.first { justEnded(it, now) }).let { t ->
            if (t.active) (if (t.steps > 0) "${t.steps} कदम" else timer(now - t.started)) else t.state
        }
        Kind.MEDIA -> if (s.media!!.playing) "▶" else "❚❚"
        Kind.LOCKED -> ""
        null -> ""
    }.let { r -> val o = others(s, now); if (o > 0) "$r +$o".trim() else r }

    /** बड़े island में एक काम की लाइनें */
    fun taskLine(t: Task, now: Long): Pair<String, String> {
        val head = "${t.who} • ${t.state}"
        val time = if (t.active) "${timer(now - t.started)} से" else ago(now - maxOf(t.ended, t.started))
        val body = t.task.take(90) + (if (t.last.isNotBlank()) "\n${t.last.take(110)}" else "") +
            "\n" + (if (t.steps > 0) "${t.steps} कदम • " else "") + time
        return head to body
    }

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when { s < 60 -> "अभी"; s < 3600 -> "${s / 60} मिनट पहले"; s < 86_400 -> "${s / 3600} घंटे पहले"; else -> "${s / 86_400} दिन पहले" }
    }

    /** [TaskStatus.Job] -> island का काम */
    fun fromJob(j: TaskStatus.Job): Task = Task(
        "${j.key}:${j.started}", j.key, j.who, j.task, j.state.hi, if (j.active) j.last else j.result.ifBlank { j.last },
        j.steps, j.started, j.ended, j.active, j.state == TaskStatus.State.DONE,
    )

    /** [TaskHistory.Entry] (फ़ोन में सेव) -> island का काम (key = id का पहला हिस्सा) */
    fun fromHistory(id: String, who: String, task: String, state: String, last: String, steps: Int, started: Long, ended: Long): Task =
        Task(id, id.substringBefore(':'), who, task, state, last, steps, started, ended, active = false, ok = state == TaskStatus.State.DONE.hi)
}
