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
        private set
    @Volatile var lastReply: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    /** "apna chehra dikhao" - is samay tak orb chehra banega */
    @Volatile var faceUntil: Long = 0L
    fun showFace(ms: Long = 12000) { faceUntil = System.currentTimeMillis() + ms; emit() }
    fun hideFace() { faceUntil = 0L; emit() }

    fun set(m: Mode) { mode = m; if (m != Mode.LISTENING) level = 0f; emit() }
    fun setLive(on: Boolean) { live = on; emit() }
    /** Chat screen ke liye poori baatcheet (role "u" = aap, "d" = DK) */
    data class Msg(val role: String, val text: String, val time: Long = System.currentTimeMillis())
    val messages = java.util.concurrent.CopyOnWriteArrayList<Msg>()

    fun user(t: String) {
        lastUser = t
        if (t.isBlank()) { emit(); return }
        if (!t.endsWith("…") && t.isNotBlank()) { messages.add(Msg("u", t)); trim() }
        emit()
    }
    /** Aakhri "aap" line ka text badlo (baad me aaya pakka text) - nayi line nahi */
    fun fixLastUser(t: String) {
        if (t.isBlank()) return
        lastUser = t
        val i = messages.indexOfLast { it.role == "u" }
        if (i >= 0) { messages[i] = messages[i].copy(text = t); persist() }
        emit()
    }
    /** Abhi ban raha (streaming) jawab - chat screen par turant dikhta hai */
    @Volatile var liveReply: String = ""
        private set
    fun reply(t: String) {
        lastReply = t; liveReply = ""
        if (t.isNotBlank()) { messages.add(Msg("d", t)); trim() }
        emit()
    }
    /** Jawab abhi ban raha hai (tukdon me) - screen par dikhao, chat list me abhi mat jodo */
    fun replyLive(t: String) { lastReply = t; liveReply = t; emit() }
    private fun trim() { while (messages.size > 200) messages.removeAt(0); persist() }

    // ---- chat phone me save (app band/restart ke baad bhi baatcheet dikhe)
    @Volatile private var appCtx: android.content.Context? = null
    private val saver = Runnable { appCtx?.let { c -> Thread { Memory.saveChat(c, messages.toList()) }.start() } }
    fun init(c: android.content.Context) {
        if (appCtx != null) return
        appCtx = c.applicationContext
        if (messages.isEmpty()) try { messages.addAll(Memory.loadChat(c)) } catch (_: Exception) {}
    }
    private fun persist() { main.removeCallbacks(saver); main.postDelayed(saver, 1500) }
    fun clearChat() { messages.clear(); persist(); emit() }

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun emit() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}
