package com.dk.assistant.fx

import com.dk.assistant.Mode
import kotlin.math.sin

/** सुनते/बोलते समय स्क्रीन के किनारों की चमक - कब और किस रंग में */
object EdgeGlowRules {
    const val VIOLET = 0xFF8B5CF6.toInt()   // सुन रहा
    const val AMBER = 0xFFFFB300.toInt()    // v3.88: सोच रहा (अलग रंग)
    const val PINK = 0xFFFF6FAE.toInt()     // बोल रहा
    const val RED = 0xFFFF7A85.toInt()      // गड़बड़

    /** null = चमक नहीं दिखानी */
    fun color(enabled: Boolean, live: Boolean, mode: Mode, error: Boolean = false): Int? {
        if (!enabled) return null
        if (error) return RED
        if (!live && mode == Mode.IDLE) return null
        return when (mode) {
            Mode.SPEAKING -> PINK
            Mode.LISTENING -> VIOLET
            Mode.THINKING, Mode.WORKING -> AMBER
            Mode.IDLE -> if (live) VIOLET else null
        }
    }

    /** चमक की तेज़ी 0..1 (धीमी साँस); motion बंद = स्थिर */
    fun intensity(mode: Mode, level: Float, t: Float, reducedMotion: Boolean): Float {
        if (reducedMotion) return 0.75f
        return when (mode) {
            Mode.LISTENING -> (0.55f + 0.45f * level.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            Mode.SPEAKING -> 0.7f + 0.3f * sin(t * 6f)
            Mode.IDLE -> 0.35f + 0.15f * sin(t * 1.5f)
            else -> 0.55f + 0.2f * sin(t * 3f)
        }.coerceIn(0f, 1f)
    }
}
