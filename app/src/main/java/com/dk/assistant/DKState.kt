package com.dk.assistant

import android.os.Handler
import android.os.Looper

enum class Mode { IDLE, LISTENING, THINKING, WORKING, SPEAKING }

/** Poore app ki live haalat - orb ka rang/animation aur screen ka text isi se chalta hai. */
object DKState {
    @Volatile var mode: Mode = Mode.IDLE
        private set
    @Volatile var level: Float = 0f          // mic ki awaaz (0..1) - orb uchhalta hai
    @Volatile var lastUser: String = ""
        private set
    @Volatile var lastReply: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun set(m: Mode) { mode = m; if (m != Mode.LISTENING) level = 0f; emit() }
    fun user(t: String) { lastUser = t; emit() }
    fun reply(t: String) { lastReply = t; emit() }

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun emit() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }
}
