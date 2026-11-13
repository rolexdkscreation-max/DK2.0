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

    data class Call(val who: String, val ringing: Boolean, val since: Long, val app: String = "", val pkg: String = "", val key: String = "", val video: Boolean = false)
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
        /** 13 नवंबर 2026 (मालिक): इसमें लिखकर जवाब का बटन है (WhatsApp / Messages / Telegram…) = island में ही "↩ जवाब" */
        val canReply: Boolean = false,
    )
    /** बैटरी: pct 0-100, charging = चार्जर लगा और भर रहा, plugAt = कब लगा, fullInMs = पूरा होने में (-1 = पता नहीं) */
    data class Charge(val pct: Int, val charging: Boolean, val full: Boolean, val plugAt: Long, val fullInMs: Long, val source: String = "")
    /** रील गिनती की पट्टी (पहले अलग popup) - अब island में. at = आख़िरी बार कब बताया */
    data class Reel(val text: String, val warn: Boolean, val at: Long)

    data class Snap(
        val call: Call? = null, val live: Live? = null, val tasks: List<Task> = emptyList(),
        val media: Media? = null, val locked: List<Locked> = emptyList(),
        val notifs: List<Notif> = emptyList(), val charge: Charge? = null, val reel: Reel? = null,
        /** 13 नवंबर 2026: सारी कॉल (पहली = [call]). खाली = सिर्फ़ [call] */
        val calls: List<Call> = emptyList(),
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
        // कई कॉल: जिस कॉल का ऐप सामने खुला है सिर्फ़ वही छिपे, बाकी island में
        val rest = s.calls.filter { c -> !(fg == c.pkg || (c.pkg.isBlank() && phoneApp(fg))) }
        return s.copy(
            call = if (s.calls.isNotEmpty()) rest.firstOrNull() else if (callFront) null else s.call,
            calls = rest,
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

    // ------------------------------------------------------------ 13 नवंबर 2026: फ़ोन के ऊपर वाले island जैसा बर्ताव
    // मालिक: "2 कॉल चल रही हैं तो अलग-अलग, वैसा ही मैनेज हो; अटकना नहीं चाहिए; छोटा-बड़ा और ऊपर से दूरी का option."
    // नियम: एक चीज़ = एक गोली (बाएँ निशान, दाएँ समय). दो या ज़्यादा = पहली गोली से जुड़ी, दूसरी अलग गोल बुलबुले में.
    // टैप = उसी चीज़ का ऐप / स्क्रीन. देर तक दबाएँ = फैलकर पूरा. नई सूचना / बजती कॉल = थोड़ी देर अपने-आप बड़ा.

    /** island की एक चीज़ (कॉल के लिए idx = कौन सी कॉल) */
    data class Item(val kind: Kind, val idx: Int = 0)

    /** सारी कॉल - [Snap.calls] खाली हो तो [Snap.call] */
    fun allCalls(s: Snap): List<Call> = s.calls.ifEmpty { listOfNotNull(s.call) }

    /** SIM + ऐप कॉल एक सूची में: बजती पहले, फिर सबसे नई; एक ही key दो बार नहीं */
    fun mergeCalls(sim: Call?, apps: List<Call>): List<Call> =
        (listOfNotNull(sim) + apps).distinctBy { it.key.ifBlank { "${it.pkg}|${it.who}" } }
            .sortedWith(compareBy<Call>({ if (it.ringing) 0 else 1 }, { -it.since }))

    /** island की चीज़ें, ज़रूरी पहले ([lead] वाला क्रम). हर कॉल अलग चीज़ */
    fun items(s: Snap, now: Long): List<Item> {
        val out = ArrayList<Item>()
        allCalls(s).indices.forEach { out.add(Item(Kind.CALL, it)) }
        val pop = s.charge?.let { chargePop(it, now) } == true
        if (pop) out.add(Item(Kind.CHARGING))
        if (s.live != null) out.add(Item(Kind.LIVE))
        if (reelOn(s.reel, now)) out.add(Item(Kind.REEL))
        val taskOn = s.tasks.any { it.active } || s.tasks.any { justEnded(it, now) }
        if (s.tasks.any { it.active }) out.add(Item(Kind.TASK))
        if (s.media?.playing == true) out.add(Item(Kind.MEDIA))
        if (taskOn && out.none { it.kind == Kind.TASK }) out.add(Item(Kind.TASK))
        if (s.locked.isNotEmpty()) out.add(Item(Kind.LOCKED))
        if (!pop && s.charge?.charging == true) out.add(Item(Kind.CHARGING))
        if (s.notifs.isNotEmpty()) out.add(Item(Kind.NOTIF))
        return out
    }

    /** अलग गोल बुलबुले में दूसरी चीज़ (null = सिर्फ़ एक गोली). चार्जर अभी लगा (बड़ा animation) = बुलबुला नहीं */
    fun bubble(s: Snap, now: Long): Item? {
        val all = items(s, now)
        if (all.isEmpty() || (all[0].kind == Kind.CHARGING && s.charge?.let { chargePop(it, now) } == true)) return null
        return all.getOrNull(1)
    }

    /** छोटी गोली के दाएँ: [compactRight] जैसा, पर बुलबुले में दिख रही चीज़ "+N" में दोबारा न गिने */
    fun compactRightWith(s: Snap, now: Long, bubbled: Boolean): String {
        val r = compactRight(s, now)
        val o = others(s, now)
        if (!bubbled || o <= 0) return r
        val base = r.removeSuffix("+$o").trim()
        val left = o - 1
        return if (left > 0) "$base +$left".trim() else base
    }

    /** दूसरी (या कोई भी) कॉल की छोटी लाइन */
    fun callText(c: Call): String = (if (c.ringing) "कॉल: " else "") + c.who.ifBlank { "अनजान नंबर" }
    fun callRight(c: Call, now: Long): String = if (c.ringing) "बज रहा" else timer(now - c.since)

    /** छोटी गोली पर टैप: true = उसी चीज़ का ऐप / स्क्रीन खुले; false = island फैले (जिसका अपना ऐप नहीं) */
    fun tapOpens(kind: Kind): Boolean = when (kind) {
        Kind.CALL, Kind.MEDIA, Kind.TASK, Kind.LIVE, Kind.NOTIF, Kind.LOCKED -> true
        Kind.CHARGING, Kind.REEL -> false
    }

    /** नई सूचना / काम पूरा = इतनी देर अपने-आप थोड़ा बड़ा (फिर छोटा) */
    const val PEEK_MS = 4_000L
    /** छूने के बाद इतनी देर ढाँचा दोबारा न बने (उँगली के नीचे से बटन न हटे = अटकना नहीं) */
    const val TOUCH_HOLD_MS = 1_200L

    /** बड़ा island अपने-आप छोटा हो? - कॉल चलते समय भी (पहले कॉल में कभी छोटा नहीं होता था = अटका). सिर्फ़ बजती कॉल रुके */
    fun autoCollapse(expanded: Boolean, idleMs: Long, fingerDown: Boolean, s: Snap): Boolean =
        expanded && !fingerDown && idleMs > AUTO_COLLAPSE_MS && allCalls(s).none { it.ringing }

    /** पहले न देखे नए notification में से सबसे नया (पहली बार = कुछ नहीं, पुराने सब देखे मान लो) */
    fun freshNotif(seen: Set<String>, cur: List<Notif>, first: Boolean): Notif? =
        if (first) null else cur.filter { it.key !in seen && !it.ongoing }.maxByOrNull { it.at }

    /** island का आकार (% में) और जगह (dp में) - Settings की पट्टी कुछ भी दे, यहाँ सही दायरे में */
    const val SIZE_MIN = 80
    const val SIZE_MAX = 130
    const val SIZE_DEF = 100
    const val TOP_MIN = 0
    const val TOP_MAX = 80
    const val TOP_DEF = 8
    const val SIDE_MIN = -120
    const val SIDE_MAX = 120
    fun size(v: Int) = v.coerceIn(SIZE_MIN, SIZE_MAX)
    fun top(v: Int) = v.coerceIn(TOP_MIN, TOP_MAX)
    fun side(v: Int) = v.coerceIn(SIDE_MIN, SIDE_MAX)
    fun sizeLabel(v: Int): String = when (size(v)) { in SIZE_MIN..90 -> "छोटा"; in 91..110 -> "मध्यम"; else -> "बड़ा" }

    /** बुलबुला गोली के दाएँ: गोली के बीच से कितनी दूर (px) */
    fun bubbleX(centerX: Int, mainW: Int, gap: Int, bubbleW: Int): Int = centerX + mainW / 2 + gap + bubbleW / 2

    // ------------------------------------------------------------ 13 नवंबर 2026 (मालिक): एक ही दिखे - Android वाला popup नहीं
    // "WhatsApp का मैसेज / कोई कॉल आए और island चालू है तो सिर्फ़ DK Island में दिखे. ऊपर से Android वाला अलग से नहीं (डबल नहीं)."

    /** Android के notification का क्या करें: NONE = छोड़ो, CANCEL = ऊपर से हटाओ (island में रहे), SNOOZE = बजती कॉल - थोड़ी देर छिपाओ */
    enum class Quiet { NONE, CANCEL, SNOOZE }

    /** बजती कॉल का Android वाला popup इतनी देर छिपे (फिर कॉल चल रही हो तो वापस आ जाता है - कुछ खोता नहीं) */
    const val CALL_SNOOZE_MS = 60_000L

    /** ये Android में ही रहें (अलार्म / रास्ता / डाउनलोड / सिस्टम…) - island में दिखें तो भी ऊपर से न हटें */
    private val KEEP_CATS = setOf("alarm", "progress", "navigation", "service", "sys", "status", "stopwatch", "transport", "location_sharing")

    /**
     * Android वाला popup हटे? सिर्फ़ तब जब: स्विच चालू, island सच में दिख सकता है, फ़ोन खुला (lock नहीं), DK / सिस्टम / गाना नहीं,
     * वही ऐप सामने नहीं (सामने वाले ऐप की चीज़ island में नहीं दिखती - तब Android वाला रहे, वरना कुछ दिखेगा ही नहीं).
     * कॉल: सिर्फ़ बजती (चलती कॉल का notification Android नहीं हटाने देता). मैसेज: island की सूची में लिया हो तभी.
     */
    fun quiet(
        pref: Boolean, islandUp: Boolean, notifsOn: Boolean, usable: Boolean, fg: String, pkg: String, own: String,
        system: Boolean, ongoing: Boolean, call: Boolean, ringing: Boolean, media: Boolean, category: String?, inIsland: Boolean,
    ): Quiet {
        if (!pref || !islandUp || !usable || pkg.isBlank() || pkg == own || system || media) return Quiet.NONE
        val front = fg.isNotBlank() && !noApp(fg) && (fg == pkg || (phoneApp(pkg) && phoneApp(fg)))
        if (call) return if (ringing && !front) Quiet.SNOOZE else Quiet.NONE
        if (ongoing || !notifsOn || !inIsland || front) return Quiet.NONE
        if (category != null && category in KEEP_CATS) return Quiet.NONE
        return Quiet.CANCEL
    }

    // ------------------------------------------------------------ 13 नवंबर 2026 (मालिक): कॉल में वहीं म्यूट / स्पीकर, मैसेज का वहीं जवाब

    /** म्यूट बटन पर क्या लिखा हो (muted = अभी माइक बंद) */
    fun muteLabel(muted: Boolean): String = if (muted) "🎙 माइक चालू" else "🔇 म्यूट"
    /** स्पीकर बटन (on = अभी स्पीकर पर) */
    fun speakerLabel(on: Boolean): String = if (on) "🔈 स्पीकर बंद" else "🔊 स्पीकर"
    /** जवाब का बटन सिर्फ़ जवाब वाले (कॉल नहीं) notification में */
    fun replyButton(n: Notif): Boolean = n.canReply && !n.call
    /** भेजने लायक जवाब (खाली / सिर्फ़ जगह नहीं), बहुत लंबा कटे */
    fun replyText(t: String): String = t.trim().take(1000)
    /** भेजने के बाद क्या दिखे (err = null -> भेज दिया) */
    fun replyResult(err: String?, to: String): String = if (err == null) "✓ ${to.ifBlank { "जवाब" }} को भेज दिया" else err
}
