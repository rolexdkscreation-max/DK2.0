package com.dk.assistant

/** कोडिंग एजेंट काम कर रहा हो तब दिखने वाला text (बिंदु 0..3 घूमते हैं). */
object BusyLabel {
    const val TICK_MS = 500L
    const val BASE = "⏳ डीके एजेंट काम कर रहा है"
    fun text(step: Int): String = BASE + ".".repeat(Math.floorMod(step, 4))
}
