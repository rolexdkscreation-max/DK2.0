package com.dk.assistant

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * DK की एक ग्लोबल "काम की हालत" - DKWorkingIndicator इसी से चलता है (हर screen पर वही एक indicator).
 *
 * अवस्थाएँ: IDLE, LISTENING, THINKING, SPEAKING, WORKING.
 * खुद-ब-खुद जुड़ता है (पुराना code नहीं छेड़ा):
 *  - DKState.mode         -> सुनना / सोचना / बोलना / फ़ोन का काम (वॉइस, चैट, मैप्स, ऐप खोलना, automation)
 *  - DKState.hintText     -> "इंटरनेट पर खोज रहा है…" = वेब सर्च
 *  - DKState.liveReply    -> जवाब stream हो रहा है (चैट में लिखना)
 *  - TaskStatus           -> कोडिंग टीम / DK कंप्यूटर का बैकग्राउंड काम + हर एजेंट के चरण का लेबल
 * नया फ़ीचर: बस `DKWork.begin(...)` / `end(id)` (या `track { }`) - अलग animation नहीं बनाना.
 * कई काम साथ = एक ही indicator चलता रहता है, आख़िरी काम खत्म होने पर रुकता है.
 */
enum class WorkState { IDLE, LISTENING, THINKING, SPEAKING, WORKING }

/** indicator को क्या दिखाना है */
data class WorkSnap(val state: WorkState, val label: String = "", val count: Int = 0) {
    val busy get() = state != WorkState.IDLE
    companion object { val IDLE = WorkSnap(WorkState.IDLE) }
}

/** हिसाब (Android import नहीं -> JVM test) */
object WorkResolver {
    /** बैकग्राउंड काम (TaskStatus job या DKWork.begin वाला) */
    data class Task(val key: String, val state: WorkState, val label: String, val since: Long = 0L)

    const val SEARCH_HI = "वेब पर खोज रहा है…"

    /**
     * @param voice  वॉइस screen: सुनना भी दिखाओ. बाकी screens पर wake-word वाला लगातार "सुनना" indicator नहीं चलाता.
     * @param focus  इस screen का अपना काम पहले (जैसे कोडिंग screen = "coder")
     */
    fun resolve(mode: Mode, hint: String, streaming: Boolean, tasks: List<Task>,
                voice: Boolean = false, focus: String? = null): WorkSnap {
        val fg: WorkSnap? = when {
            mode == Mode.SPEAKING -> WorkSnap(WorkState.SPEAKING, if (voice) "बोल रहा है…" else "जवाब लिख रहा है…")
            streaming && mode != Mode.LISTENING -> WorkSnap(WorkState.SPEAKING, "जवाब लिख रहा है…")
            mode == Mode.THINKING -> WorkSnap(WorkState.THINKING, searchLabel(hint) ?: hint.ifBlank { "सोच रहा है…" })
            mode == Mode.WORKING -> WorkSnap(WorkState.WORKING, hint.ifBlank { "फ़ोन पर काम कर रहा है…" })
            mode == Mode.LISTENING && voice -> WorkSnap(WorkState.LISTENING, "सुन रहा है…")
            else -> null
        }
        val bg = tasks.sortedWith(compareByDescending<Task> { it.key == focus }.thenBy { it.since })
        val count = bg.size + (if (fg != null) 1 else 0)
        val focused = bg.firstOrNull { it.key == focus }
        return when {
            // अपनी screen का काम सबसे पहले (वॉइस में सुनना/बोलना भी उसके ऊपर नहीं - वहाँ fg ही)
            focused != null && !voice -> WorkSnap(focused.state, focused.label, count)
            fg != null -> WorkSnap(fg.state, fg.label + more(bg.size), count)
            bg.isNotEmpty() -> WorkSnap(bg[0].state, bg[0].label + more(bg.size - 1), count)
            else -> WorkSnap.IDLE
        }
    }

    private fun more(n: Int) = if (n > 0) "  +$n" else ""

    /** hint में खोज लिखा हो = वेब सर्च */
    fun searchLabel(hint: String): String? =
        if (hint.contains("खोज") || hint.contains("search", true)) hint.ifBlank { SEARCH_HI } else null

    /**
     * कोडिंग टीम के कदम "बिल्डर: ...", "🔍 रिव्यूअर: ..." से छोटा लेबल:
     * कोड लिखने वाला / बग जाँचने वाला / कोऑर्डिनेटर (+ बाकी एजेंट अपने नाम से).
     */
    fun roleLabel(step: String): String {
        val t = step.trim()
        val i = t.indexOf(':')
        if (i <= 0 || i > 40) return t.take(60)
        val who = t.substring(0, i).replace(Regex("[^\\p{L}\\p{M}\\s]"), "").trim()
        val rest = t.substring(i + 1).trim().lineSequence().firstOrNull().orEmpty()
        val role = when {
            who.contains("बिल्डर") || who.contains("कोडर") -> "कोड लिखने वाला"
            who.contains("रिव्यूअर") || who.contains("डिबगर") || who.contains("टेस्टर") || who == "QA" || who.contains("जाँचकर्ता") -> "बग जाँचने वाला"
            who.contains("कोऑर्डिनेटर") || who.contains("मैनेजर") || who.contains("प्लानर") -> "कोऑर्डिनेटर"
            who.isBlank() -> return t.take(60)
            else -> who
        }
        return if (rest.isBlank()) role else "$role • " + rest.take(44)
    }
}

/** ग्लोबल हालत + listeners (main thread पर) */
object DKWork {
    private val manual = java.util.concurrent.ConcurrentHashMap<Long, WorkResolver.Task>()
    private val ids = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var bridged = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val notify = Runnable { listeners.forEach { try { it() } catch (_: Exception) {} } }

    /** नया काम शुरू (कोई भी फ़ीचर). लौटा id `end` में दो. */
    fun begin(label: String, state: WorkState = WorkState.WORKING): Long {
        val id = ids.incrementAndGet()
        manual[id] = WorkResolver.Task("m$id", state, label, System.currentTimeMillis())
        changed(); return id
    }
    fun update(id: Long, label: String) { manual[id]?.let { manual[id] = it.copy(label = label); changed() } }
    fun end(id: Long) { if (manual.remove(id) != null) changed() }
    suspend fun <T> track(label: String, state: WorkState = WorkState.WORKING, block: suspend () -> T): T {
        val id = begin(label, state)
        try { return block() } finally { end(id) }
    }

    /** इस screen के लिए अभी की हालत */
    fun snapshot(voice: Boolean = false, focus: String? = null): WorkSnap {
        val tasks = ArrayList<WorkResolver.Task>(manual.values)
        for (j in TaskStatus.all()) if (j.active) tasks.add(WorkResolver.Task(j.key,
            if (j.state == TaskStatus.State.WAITING) WorkState.THINKING else WorkState.WORKING,
            j.who + if (j.state == TaskStatus.State.WAITING) " • आपकी इजाज़त का इंतज़ार"
                    else j.last.takeIf { it.isNotBlank() }?.let { " • " + WorkResolver.roleLabel(it) }.orEmpty(),
            j.started))
        return WorkResolver.resolve(DKState.shownMode(), DKState.hintText, DKState.liveReply.isNotBlank(), tasks, voice, focus)
    }

    fun listen(l: () -> Unit) { bridge(); listeners.addIfAbsent(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun changed() { main.removeCallbacks(notify); main.post(notify) }

    /** DKState + TaskStatus से एक बार जुड़ो (उनका code नहीं बदला) */
    private fun bridge() {
        if (bridged) return
        bridged = true
        DKState.listen { changed() }
        TaskStatus.listen { _, _ -> changed() }
    }
}
