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

    fun set(m: Mode) { mode = m; if (m != Mode.LISTENING) level = 0f; emit() }
    fun setLive(on: Boolean) { live = on; emit() }
    /** Chat screen ke liye poori baatcheet (role "u" = aap, "d" = DK) */
    data class Msg(val role: String, val text: String, val time: Long = System.currentTimeMillis())
    val messages = java.util.concurrent.CopyOnWriteArrayList<Msg>()

    fun user(t: String) {
        lastUser = t
        if (!t.endsWith("…") && t.isNotBlank()) { messages.add(Msg("u", t)); trim() }
        emit()
    }
    fun reply(t: String) {
        lastReply = t
        if (t.isNotBlank()) { messages.add(Msg("d", t)); trim() }
        emit()
    }
    private fun trim() { while (messages.size > 200) messages.removeAt(0) }

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun emit() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}
