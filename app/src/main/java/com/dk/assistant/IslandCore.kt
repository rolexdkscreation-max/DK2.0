package com.dk.assistant

/**
 * DK Island - मालिक (12 नवंबर 2026): "DK का कोई भी काम चले और मैं DK से बाहर हूँ, तो स्क्रीन के ऊपर बीच में दिखे.
 * टैप करो तो अंदर जो काम चल रहा है - हाल के 1-5 काम - सब दिखें. कॉल आए तो कॉल जैसा, गाना बजे (किसी भी ऐप / YouTube) तो वह भी."
 * 13 नवंबर 2026 (मालिक): नाम सिर्फ़ "DK Island". फ़ोन पर आने वाले सारे notification इसी में (स्लाइड करके हटाओ, आख़िर में
 * "सब साफ़ करें"), टैप = जिसका notification उसी जगह खुले (कॉल पर टैप = चलती कॉल). चार्जिंग का animation + % + कितनी देर में पूरा.
 * YouTube / फेसबुक / इंस्टाग्राम की रील गिनती और सोशल काम / रिव्यू भी इसी के अंदर.
 *
 * यह फ़ाइल सिर्फ़ नियम है (Android import नहीं -> JVM test): island दिखे या नहीं, छोटे island में सबसे ऊपर क्या, बड़े में कौन-से काम.
 * सब कुछ असली हालत से ([TaskStatus], [TaskHistory], कॉल, मीडिया सेशन) - कोई नकली % नहीं.
 */
object IslandCore {

    enum class Kind { CALL, LIVE, TASK, MEDIA, LOCKED, NOTIF, CHARGING, REEL }

    data class Call(val who: String, val ringing: Boolean, val since: Long, val app: String = "", val pkg: String = "")
    data class Live(val mode: Mode, val muted: Boolean)
    data class Task(
        val id: String, val key: String, val who: String, val task: String, val state: String,
        val last: String, val steps: Int, val started: Long, val ended: Long, val active: Boolean,
        val ok: Boolean = false,
    )
    data class Media(val title: String, val artist: String, val app: String, val pkg: String, val playing: Boolean)
    /** DK Privacy ने छिपाया notification - island में सिर्फ़ "🔒 ऐप • N नए" (अंदर की बात नहीं) */
    data class Locked(val key: String, val pkg: String, val app: String, val count: Int, val at: Long)

    /** फ़ोन पर आया कोई भी notification (DK का अपना / group summary / गाने वाला नहीं). call = कॉल का notification (टैप = चलती कॉल) */
    data class Notif(
        val key: String, val pkg: String, val app: String, val title: String, val text: String, val at: Long,
        val ongoing: Boolean = false, val call: Boolean = false,
    )
    /** बैटरी: pct 0-100, charging = चार्जर लगा और भर रहा, plugAt = कब लगा, fullInMs = पूरा होने में (-1 = पता नहीं) */
    data class Charge(val pct: Int, val charging: Boolean, val full: Boolean, val plugAt: Long, val fullInMs: Long, val source: String = "")
    /** रील गिनती की पट्टी (पहले अलग popup) - अब island में. at = आख़िरी बार कब बताया */
    data class Reel(val text: String, val warn: Boolean, val at: Long)

    data class Snap(
        val call: Call? = null, val live: Live? = null, val tasks: List<Task> = emptyList(),
        val media: Media? = null, val locked: List<Locked> = emptyList(),
        val notifs: List<Notif> = emptyList(), val charge: Charge? = null, val reel: Reel? = null,
    )

    /** बड़े island में ज़्यादा से ज़्यादा इतने हाल के काम (मालिक: "1, 2, 3, 4, 5") */
    const val MAX_TASKS = 5
    /** काम खत्म होने के बाद इतनी देर छोटा island "✓ पूरा" दिखाए */
    const val DONE_SHOW_MS = 8_000L
    /** इतनी देर कोई छुए नहीं तो बड़ा island अपने-आप छोटा */
    const val AUTO_COLLAPSE_MS = 9_000L
    /** island में ज़्यादा से ज़्यादा इतने notification */
    const val MAX_NOTIFS = 50
    /** चार्जर लगते ही इतनी देर बड़ा चार्जिंग animation (फिर छोटा ⚡ %) */
    const val CHARGE_POP_MS = 5_000L
    /** रील गिनती: इतनी देर नई गिनती न आए (रील से बाहर) तो island में से हटे */
    const val REEL_SHOW_MS = 2_500L

    /** island दिखे? Settings में चालू + DK ऐप सामने नहीं + कुछ दिखाने लायक */
    fun wanted(prefOn: Boolean, inApp: Boolean, s: Snap, now: Long): Boolean = prefOn && !inApp && lead(s, now) != null

    /**
     * छोटे island में सबसे ऊपर क्या: कॉल > चार्जर अभी लगा (कुछ सेकंड) > DK लाइव > रील गिनती > चलता काम > बजता गाना >
     * अभी खत्म हुआ काम > छिपे notification > चार्जिंग > बाकी notification
     */
    fun lead(s: Snap, now: Long): Kind? = when {
        s.call != null -> Kind.CALL
        s.charge?.let { chargePop(it, now) } == true -> Kind.CHARGING
        s.live != null -> Kind.LIVE
        reelOn(s.reel, now) -> Kind.REEL
        s.tasks.any { it.active } -> Kind.TASK
        s.media?.playing == true -> Kind.MEDIA
        s.tasks.any { justEnded(it, now) } -> Kind.TASK
        s.locked.isNotEmpty() -> Kind.LOCKED
        s.charge?.charging == true -> Kind.CHARGING
        s.notifs.isNotEmpty() -> Kind.NOTIF
        else -> null
    }

    /** चार्जर अभी-अभी लगा = बड़ा animation */
    fun chargePop(c: Charge, now: Long) = c.charging && c.plugAt > 0 && now - c.plugAt in 0..CHARGE_POP_MS
    fun reelOn(r: Reel?, now: Long) = r != null && r.text.isNotBlank() && now - r.at in 0..REEL_SHOW_MS

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
        if (reelOn(s.reel, now) && l != Kind.REEL) n++
        if (s.charge?.charging == true && l != Kind.CHARGING) n++
        if (s.notifs.isNotEmpty() && l != Kind.NOTIF) n++
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
        Kind.CHARGING -> "⚡ ${s.charge!!.pct}%"
        Kind.REEL -> s.reel!!.text.lineSequence().first()
        Kind.NOTIF -> s.notifs.first().let { n -> n.app + (n.title.takeIf { it.isNotBlank() && it != n.app }?.let { ": $it" } ?: "") }
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
        Kind.CHARGING -> s.charge!!.let { if (it.full || it.pct >= 100) "पूरा" else shortLeft(it.fullInMs) }
        Kind.REEL -> ""
        Kind.NOTIF -> "🔔 ${s.notifs.size}"
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

    // ------------------------------------------------------------ notification (13 नवंबर 2026)
    /** island की सूची: एक key एक बार (नया वाला), कॉल सबसे ऊपर, फिर नए से पुराने; ज़्यादा से ज़्यादा [MAX_NOTIFS] */
    fun notifOrder(all: List<Notif>, max: Int = MAX_NOTIFS): List<Notif> {
        val byKey = LinkedHashMap<String, Notif>()
        for (n in all) { val o = byKey[n.key]; if (o == null || n.at >= o.at) byKey[n.key] = n }
        return byKey.values.sortedWith(compareByDescending<Notif> { it.call }.thenByDescending { it.at }).take(max)
    }

    /** स्लाइड: चौड़ाई का 35% (कम से कम 1px) से ज़्यादा खींचा = हटाओ, नहीं तो वापस अपनी जगह */
    fun swipeDismiss(dx: Float, width: Int): Boolean = kotlin.math.abs(dx) >= maxOf(1f, width * 0.35f)

    /** "सब साफ़ करें" के बाद फ़ोन में क्या बचता है: चलते (ongoing - जैसे चलती कॉल / download) Android हटाने नहीं देता */
    fun afterClearAll(all: List<Notif>): List<Notif> = all.filter { it.ongoing }

    // ------------------------------------------------------------ चार्जिंग
    /** 65 मिनट -> "पूरा होने में 1 घंटा 5 मिनट" */
    fun chargeLeft(ms: Long, full: Boolean = false): String = when {
        full -> "पूरा चार्ज हो गया"
        ms < 0 -> "समय निकाल रहा हूँ…"
        ms < 60_000 -> "पूरा होने में 1 मिनट से कम"
        else -> {
            val m = (ms + 30_000) / 60_000
            "पूरा होने में " + (if (m >= 60) "${m / 60} घंटा" + (if (m % 60 > 0) " ${m % 60} मिनट" else "") else "$m मिनट")
        }
    }

    /** छोटे island की दाईं ओर: "1 घं 5 मि" / "45 मि" / "⚡" (पता नहीं) */
    fun shortLeft(ms: Long): String = when {
        ms < 0 -> "⚡"
        ms < 60_000 -> "<1 मि"
        else -> { val m = (ms + 30_000) / 60_000; if (m >= 60) "${m / 60} घं" + (if (m % 60 > 0) " ${m % 60} मि" else "") else "$m मि" }
    }

    /**
     * Android अंदाज़ा न दे (-1) तो खुद: चार्जर लगने के बाद के (समय, %) नमूनों से रफ़्तार.
     * कम से कम 1% बढ़ा और 1 मिनट बीता हो, नहीं तो -1 (कोई नकली समय नहीं).
     */
    fun estimateFullMs(samples: List<Pair<Long, Int>>, pct: Int): Long {
        if (pct >= 100) return 0L
        if (samples.size < 2) return -1L
        val a = samples.first(); val b = samples.last()
        val dp = b.second - a.second; val dt = b.first - a.first
        if (dp < 1 || dt < 60_000L) return -1L
        return ((100 - pct).toDouble() * dt / dp).toLong()
    }

    /** बैटरी भरने का रंग: कम = लाल, बीच = पीला, बाकी हरा */
    fun batteryColor(pct: Int): Long = when { pct < 20 -> 0xFFEF4444; pct < 50 -> 0xFFF59E0B; else -> 0xFF22C55E }

    fun chargeSource(plugged: Int): String = when (plugged) { 1 -> "चार्जर (AC)"; 2 -> "USB"; 4 -> "वायरलेस"; else -> "" }

    // ------------------------------------------------------------ सोशल काम (YouTube / Facebook / Instagram…) island में
    /** SocialAgent की हालत (enum का नाम) -> island का काम */
    fun fromSocial(id: String, app: String, action: String, status: String, message: String, step: Int, total: Int,
                   started: Long, ended: Long): Task {
        val finished = status in setOf("COMPLETED", "SUBMITTED_UNVERIFIED", "DRAFT_READY", "FAILED", "CANCELLED")
        val state = when (status) {
            "RUNNING" -> TaskStatus.State.RUNNING.hi
            "WAITING_CONFIRMATION", "WAITING_USER" -> TaskStatus.State.WAITING.hi
            "COMPLETED" -> TaskStatus.State.DONE.hi
            "SUBMITTED_UNVERIFIED" -> "भेजा (पक्का नहीं)"
            "DRAFT_READY" -> "ड्राफ़्ट तैयार"
            "FAILED" -> TaskStatus.State.FAILED.hi
            "CANCELLED" -> TaskStatus.State.CANCELLED.hi
            else -> status.lowercase()
        }
        val what = action.lowercase().replace('_', ' ')
        val last = message + if (total > 0 && !finished) " ($step/$total)" else ""
        return Task("social:$id", "social", app, "$app: $what", state, last, step, started, if (finished) maxOf(ended, started) else 0L,
            active = !finished, ok = status == "COMPLETED")
    }

    // ------------------------------------------------------------ सिर्फ़ पीछे (background) चलती चीज़ें (13 नवंबर 2026, मालिक)
    /**
     * मालिक: "DK Island में वही दिखे जो बैकग्राउंड में चल रहा है - YouTube वहीं देख रहा हूँ तो नहीं, YouTube पीछे किया तो दिखे.
     * गाना हो या कुछ भी - उसी ऐप में हूँ तो नहीं दिखे."
     * fg = अभी सामने वाला ऐप ("" / launcher / systemui = कोई ऐप सामने नहीं = सब दिखे).
     * हटता है: सामने वाले ऐप का गाना/वीडियो, उसके notification, उसके छिपे (DK Privacy) notification, और कॉल अगर कॉल वाली स्क्रीन ही सामने है.
     * रहता है: DK के अपने काम (PC / Coder / Studio / सोशल रिव्यू), DK लाइव, रील गिनती (DK पीछे से गिनता है), चार्जिंग.
     */
    fun background(s: Snap, fg: String): Snap {
        if (fg.isBlank() || noApp(fg)) return s
        val callFront = s.call != null && (fg == s.call.pkg || (s.call.pkg.isBlank() && phoneApp(fg)))
        return s.copy(
            call = if (callFront) null else s.call,
            media = s.media?.takeIf { it.pkg != fg },
            notifs = s.notifs.filter { it.pkg != fg },
            locked = s.locked.filter { it.pkg != fg },
        )
    }

    /** फ़ोन की अपनी (SIM) कॉल वाली स्क्रीन (AppCall.isPhoneApp जैसा) */
    fun phoneApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return listOf("dialer", "incallui", "telecom", "com.android.phone", "com.samsung.android.app.telephonyui", "contacts").any { p.contains(it) }
    }

    /** होम स्क्रीन / ऊपर का पर्दा = कोई ऐप सामने नहीं */
    fun noApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == "com.android.systemui" || p.contains("launcher") || p == "com.google.android.apps.nexuslauncher" || p == "com.sec.android.app.launcher"
    }
}
