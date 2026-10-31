package com.dk.assistant

import android.os.Handler
import android.os.Looper

enum class Mode { IDLE, LISTENING, THINKING, WORKING, SPEAKING }

/** Poore app ki live haalat - orb ka rang/animation aur screen ka text isi se chalta hai. */
object DKState {
    @Volatile var mode: Mode = Mode.IDLE
        private set
    /** Live mode: ek baar ON = lagatar sunna, jab tak band na karo */
    @Volatile var live: Boolean = false
        private set
    @Volatile var level: Float = 0f          // mic ki awaaz (0..1) - orb uchhalta hai
    @Volatile var lastUser: String = ""
    /** DK AI Part 12: यूज़र की आख़िरी बात कब आई (पोस्ट/भेजने से पहले "हाँ" पूछने के बाद आई हो) */
    @Volatile var lastUserAt: Long = 0L
        private set
    @Volatile var lastReply: String = ""
        private set
    /** Aakhri jawab ke sources (voice screen ke caption ke neeche) - khali = apne gyaan se jawab */
    @Volatile var lastSources: List<Pair<String, String>> = emptyList()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    /** "apna chehra dikhao" - is samay tak orb chehra banega */
    @Volatile var faceUntil: Long = 0L
    fun showFace(ms: Long = 12000) { faceUntil = System.currentTimeMillis() + ms; emit() }
    fun hideFace() { faceUntil = 0L; emit() }

    fun set(m: Mode) { mode = m; if (m != Mode.LISTENING) level = 0f; emit() }

    /**
     * v5.1: DK Live का जुड़ाव - स्क्रीन पर साफ़ दिखे (कभी चुपचाप नाकाम नहीं):
     * "जुड़ रहा है… <मॉडल> • key #N", "Live • <मॉडल> • key #N • 820 ms", "धीमा नेट - हल्का मोड", "ऑफ़लाइन". key कभी नहीं, सिर्फ़ नंबर
     */
    @Volatile var linkStatus = ""
        private set
    const val LINK_LIGHT = "धीमा नेट - हल्का मोड"
    const val LINK_OFFLINE = "ऑफ़लाइन"
    fun setLink(s: String) { if (linkStatus != s) { linkStatus = s; emit() } }

    /**
     * v3.90: DK Live में "Thinking" सिर्फ़ तब, जब सच में खोज / गहरी सोच / फ़ोन का काम चल रहा हो.
     * आम बात ("कैसे हो") में सुनना -> सीधे बोलना (बीच का पल "Listening" ही दिखे). GeminiLive यह देता है;
     * बाक़ी हर जगह हमेशा true = पहले जैसा.
     */
    @Volatile var workNow: () -> Boolean = { true }
    fun working(): Boolean = try { workNow() } catch (_: Exception) { true }
    fun refresh() = emit()
    /** स्क्रीन पर दिखने वाली हालत: Live में बिना काम का THINKING = LISTENING (असली [mode] नहीं बदलता - अंदर के नियम वैसे ही) */
    fun shownMode(): Mode = if (mode == Mode.THINKING && live && !working()) Mode.LISTENING else mode
    fun setLive(on: Boolean) { live = on; emit() }
    /** होम स्क्रीन का म्यूट बटन: true = DK का माइक कुछ नहीं भेजता/सुनता (Live में खामोशी जाती है), दोबारा दबाने तक */
    @Volatile var micMuted: Boolean = false
        private set
    fun setMicMuted(on: Boolean) { micMuted = on; emit() }
    /** Chat screen ke liye poori baatcheet (role "u" = aap, "d" = DK) */
    data class Msg(val role: String, val text: String, val time: Long = System.currentTimeMillis(),
                   /** Web search ke sources (title, url) - sirf jab jawab sach me search se aaya; warna khali */
                   val src: List<Pair<String, String>> = emptyList())
    val messages = java.util.concurrent.CopyOnWriteArrayList<Msg>()

    fun user(t: String) {
        lastUser = t
        if (t.isNotBlank()) lastUserAt = System.currentTimeMillis()
        // v3.87: पढ़ाई की बात = टीचर वाला पन्ना अपने-आप (आधी "…" बात पर भी, ताकि जवाब शुरू होते ही दिखे)
        if (t.isNotBlank()) TeacherLink.onUser(appCtx, t)
        if (t.isBlank()) { emit(); return }
        // chat screen ne yahi message "Send" dabate hi khud dikha diya tha (optimistic) - dobara mat jodo
        val opt = optimistic
        if (opt != null && opt.first == t && System.currentTimeMillis() - opt.second < 15_000) { optimistic = null; emit(); return }
        if (!t.endsWith("…") && t.isNotBlank()) { messages.add(Msg("u", t)); trim(); LocalMemory.record(appCtx, "u", t, if (live) "voice" else "text") }
        emit()
    }

    /** v3.8 chat: "Send" dabate hi apna message TURANT list me (server/AI ka intezaar nahi). Main thread se hi bulao. */
    @Volatile private var optimistic: Pair<String, Long>? = null
    /** Aakhri message kab bheja (deri naapne ke liye: Send -> AI request -> pehla shabd) */
    @Volatile var sentAt = 0L
        private set
    fun userNow(t: String) {
        if (t.isBlank()) return
        hintText = ""
        lastUser = t
        sentAt = System.currentTimeMillis()
        optimistic = t to sentAt
        messages.add(Msg("u", t)); trim()
        LocalMemory.record(appCtx, "u", t, "text")     // v3.88: लोकल याददाश्त - तुरंत
        mode = Mode.THINKING; level = 0f      // typing indicator turant (listener seedha, post ka intezaar nahi)
        val copy = synchronized(listeners) { listeners.toList() }
        if (Looper.myLooper() == Looper.getMainLooper()) copy.forEach { it() } else main.post { copy.forEach { it() } }
    }

    /** Jawab nahi aa paya (network/AI galti) - chat screen "फिर से भेजें" dikhaye. Value = us DK message ka time */
    @Volatile var failedAt = 0L
        private set
    fun replyFailed(t: String) {
        lastReply = t; liveReply = ""; hintText = ""; lastSources = emptyList()
        val m = Msg("d", t); failedAt = m.time
        messages.add(m); trim()
        emit()
    }
    /** Aakhri "aap" line ka text badlo (baad me aaya pakka text) - nayi line nahi */
    fun fixLastUser(t: String) {
        if (t.isBlank()) return
        lastUser = t
        val i = messages.indexOfLast { it.role == "u" }
        if (i >= 0) { messages[i] = messages[i].copy(text = t); persist() }
        LocalMemory.fixLastUser(appCtx, t)
        emit()
    }
    /** Abhi ban raha (streaming) jawab - chat screen par turant dikhta hai */
    @Volatile var liveReply: String = ""
        private set
    /** Chat ke typing indicator me kya likhen (khoj/gehri soch = lamba samay theek hai) */
    @Volatile var hintText: String = ""
        private set
    fun hint(t: String) { hintText = t; emit() }
    fun reply(t: String, sources: List<Pair<String, String>> = emptyList()) {
        lastReply = t; liveReply = ""; hintText = ""; lastSources = sources
        TeacherBoard.onReply(t, final = true)   // v3.87: पढ़ाई का जवाब टीचर के पन्ने पर
        if (t.isNotBlank()) { messages.add(Msg("d", t, src = sources)); trim(); LocalMemory.record(appCtx, "d", t, if (live) "voice" else "text") }
        emit()
    }
    /** Jawab abhi ban raha hai (tukdon me) - screen par dikhao, chat list me abhi mat jodo */
    fun replyLive(t: String) { if (liveReply.isEmpty()) lastSources = emptyList(); lastReply = t; liveReply = t; TeacherBoard.onReply(t, final = false); emit() }
    private fun trim() { while (messages.size > 200) messages.removeAt(0); persist() }

    // ---- chat phone me save (app band/restart ke baad bhi baatcheet dikhe)
    @Volatile var appCtx: android.content.Context? = null
        private set
    private val saver = Runnable { appCtx?.let { c -> Thread { Memory.saveChat(c, messages.toList()) }.start() } }
    fun init(c: android.content.Context) {
        if (appCtx != null) return
        appCtx = c.applicationContext
        if (messages.isEmpty()) try { messages.addAll(Memory.loadChat(c)) } catch (_: Exception) {}
    }
    private fun persist() { main.removeCallbacks(saver); main.postDelayed(saver, 1500) }
    fun clearChat() { messages.clear(); persist(); emit() }

    /** Reset chat history, active states and clear workspace memory to fresh state on reset trigger */
    fun resetToFresh() {
        messages.clear()
        lastUser = ""
        lastReply = ""
        liveReply = ""
        hintText = ""
        lastSources = emptyList()
        failedAt = 0L
        sentAt = 0L
        live = false
        mode = Mode.IDLE
        level = 0f
        faceUntil = 0L
        optimistic = null
        appCtx?.let { c ->
            Thread {
                try {
                    Memory.saveChat(c, emptyList())
                } catch (_: Exception) {}
            }.start()
        }
        emit()
    }

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    /**
     * v3.41: DK बोलते समय हर टुकड़े पर (कई बार/सेकंड) हर screen पूरा render करती थी. अब कई बदलाव एक साथ:
     * ज़्यादा से ज़्यादा ~30 बार/सेकंड (screen हमेशा आख़िरी हालत दिखाती है, कुछ छूटता नहीं).
     */
    private val emitPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastEmit = 0L
    private val emitter = Runnable {
        emitPending.set(false)
        lastEmit = android.os.SystemClock.uptimeMillis()
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { try { it() } catch (_: Exception) {} }
    }
    private fun emit() {
        if (!emitPending.compareAndSet(false, true)) return
        main.postDelayed(emitter, UiPace.emitWait(lastEmit, android.os.SystemClock.uptimeMillis()))
    }
}