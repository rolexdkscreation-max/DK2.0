package com.dk.assistant.fx

import com.dk.assistant.Mode
import kotlin.math.sin

/**
 * v3.57 (Part 10): "3D रोशनी का गोला" ओर्ब के रंग और चाल - सिर्फ़ हिसाब (JVM test होते हैं)।
 * असली ड्रॉइंग Sphere3D (Android 13+ का AGSL shader) करता है।
 */
data class OrbPalette(val core: Int, val glow: Int, val rim: Int)

object OrbLook {
    const val STYLE = "sphere3d"

    /** हालत -> रंग: तैयार = कमल/बैंगनी, सुन रहा = लैगून फ़िरोज़ी, सोच = गहरा बैंगनी,
     *  काम/जुड़ रहा = अंबर, बोल रहा = गेंदा-गुलाबी, गड़बड़ = धीमा लाल */
    fun palette(mode: Mode, error: Boolean = false): OrbPalette = when {
        error -> OrbPalette(0xFF7A1F2B.toInt(), 0xFFFF7A85.toInt(), 0xFFFFB3B9.toInt())
        else -> when (mode) {
            Mode.IDLE -> OrbPalette(0xFF6D28D9.toInt(), 0xFFE879F9.toInt(), 0xFFF5D0FE.toInt())
            Mode.LISTENING -> OrbPalette(0xFF0F766E.toInt(), 0xFF2DD4BF.toInt(), 0xFFA7F3D0.toInt())
            Mode.THINKING -> OrbPalette(0xFF4C1D95.toInt(), 0xFF8B5CF6.toInt(), 0xFFC4B5FD.toInt())
            Mode.WORKING -> OrbPalette(0xFF92400E.toInt(), 0xFFF59E0B.toInt(), 0xFFFDE68A.toInt())
            Mode.SPEAKING -> OrbPalette(0xFFF59E0B.toInt(), 0xFFFF6FAE.toInt(), 0xFFFFE4F1.toInt())
        }
    }

    /** अंदर के बादलों के घूमने की रफ़्तार */
    fun swirl(mode: Mode): Float = when (mode) {
        Mode.IDLE -> 0.25f; Mode.LISTENING -> 0.45f; Mode.THINKING -> 1.6f
        Mode.WORKING -> 0.35f; Mode.SPEAKING -> 0.8f
    }

    /** 0..1 - सतह कितनी उछले (सुनते समय माइक, बोलते समय लय) */
    fun amplitude(mode: Mode, micLevel: Float, t: Float): Float = when (mode) {
        Mode.LISTENING -> (0.15f + micLevel * 0.85f).coerceIn(0f, 1f)
        Mode.SPEAKING -> (0.45f + 0.35f * sin(t * 9f)).coerceIn(0f, 1f)
        Mode.THINKING -> 0.25f
        else -> 0.08f
    }

    /** गोले का आकार (साँस लेना) - motion बंद हो तो स्थिर 1 */
    fun scale(mode: Mode, t: Float, reducedMotion: Boolean): Float {
        if (reducedMotion) return 1f
        val breathe = if (mode == Mode.LISTENING) 0.05f else 0.025f
        return 1f + breathe * sin(t * 1.6f)
    }

    /** दो रंगों का धीमा मेल (हालत बदलते ही झटका न लगे) */
    fun lerp(a: Int, b: Int, k: Float): Int {
        val f = k.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f)).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** झुकाव सेंसर का low-pass (हाथ काँपे तो रोशनी न हिले) */
    fun lowPass(old: Float, new: Float, alpha: Float = 0.08f): Float = old + (new - old) * alpha
}

/** सुनते/बोलते समय स्क्रीन के किनारों की चमक - कब और किस रंग में */
object EdgeGlowRules {
    const val VIOLET = 0xFF8B5CF6.toInt()   // सुन रहा / सोच रहा
    const val PINK = 0xFFFF6FAE.toInt()     // बोल रहा
    const val RED = 0xFFFF7A85.toInt()      // गड़बड़

    /** null = चमक नहीं दिखानी */
    fun color(enabled: Boolean, live: Boolean, mode: Mode, error: Boolean = false): Int? {
        if (!enabled) return null
        if (error) return RED
        if (!live && mode == Mode.IDLE) return null
        return when (mode) {
            Mode.SPEAKING -> PINK
            Mode.LISTENING, Mode.THINKING, Mode.WORKING -> VIOLET
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
