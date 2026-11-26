package com.dk.assistant.dkai

import com.dk.assistant.Mode

/**
 * 26 नवंबर 2026 (मालिक): ethereal AI कैरेक्टर (महिला / पुरुष तस्वीर) की हालत.
 * हालत बदलने से सिर्फ़ चमक / कण / धड़कन बदलते हैं - कैरेक्टर की पहचान (तस्वीर) कभी नहीं.
 */
enum class SpiritState { IDLE, LISTENING, THINKING, SPEAKING, ACTIVE }

/**
 * आवाज़ सिस्टम और कैरेक्टर के बीच का साफ़ पुल. आवाज़ इंजन को छूता नहीं - सिर्फ़ उसकी हालत पढ़ता है
 * ([com.dk.assistant.DKState.mode], TTS की आवाज़ [com.dk.assistant.voice.engine.VoiceLevel.mouth]).
 * कोई listener / thread नहीं, इसलिए आवाज़ चलाने में कभी रुकावट नहीं. हर frame में [tick] बुलाओ.
 */
class SpiritVoiceBridge(
    private val mode: () -> Mode = { com.dk.assistant.DKState.mode },
    private val amp: () -> Float? = { com.dk.assistant.voice.engine.VoiceLevel.mouth() },
) {
    var state: SpiritState = SpiritState.IDLE
        private set
    /** बोलते समय TTS की आवाज़ 0..1 (नहीं मिली = -1, तब इंजन धीमी नरम धड़कन चलाता है) */
    var amplitude: Float = -1f
        private set
    private var activeUntil = 0L
    private var wasWorking = false

    fun tick(now: Long): SpiritState {
        val m = try { mode() } catch (e: Exception) { com.dk.assistant.Quiet.note(e); Mode.IDLE }
        state = next(m, now)
        amplitude = if (state == SpiritState.SPEAKING) {
            (try { amp() } catch (e: Exception) { com.dk.assistant.Quiet.note(e); null })?.coerceIn(0f, 1f) ?: -1f
        } else {
            -1f
        }
        return state
    }

    /** शुद्ध state machine (टेस्ट के लिए): WORKING = ACTIVE, खत्म होने के बाद थोड़ी देर ACTIVE फिर IDLE */
    fun next(m: Mode, now: Long): SpiritState {
        if (m == Mode.WORKING) { wasWorking = true; activeUntil = now + ACTIVE_HOLD_MS; return SpiritState.ACTIVE }
        if (wasWorking) { wasWorking = false; activeUntil = now + ACTIVE_HOLD_MS }
        return when (m) {
            Mode.LISTENING -> SpiritState.LISTENING
            Mode.THINKING -> SpiritState.THINKING
            Mode.SPEAKING -> SpiritState.SPEAKING
            else -> if (now < activeUntil) SpiritState.ACTIVE else SpiritState.IDLE
        }
    }

    /** कहीं से (जैसे टैप) छोटी सी ACTIVE चमक - फिर अपने-आप IDLE */
    fun pulse(now: Long) { activeUntil = now + ACTIVE_HOLD_MS }

    companion object {
        const val ACTIVE_HOLD_MS = 1500L

        /** हर हालत में चमक कितनी (0..1) - बहुत धीरे बदलती है, कभी चमकती-बुझती नहीं */
        fun aura(s: SpiritState): Float = when (s) {
            SpiritState.IDLE -> 0.34f
            SpiritState.LISTENING -> 0.48f
            SpiritState.THINKING -> 0.52f
            SpiritState.SPEAKING -> 0.44f
            SpiritState.ACTIVE -> 0.62f
        }
    }
}
