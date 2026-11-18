package com.dk.assistant.stt

/**
 * 18 नवंबर 2026 (मालिक: "Whisper चुनूँ या Android; Whisper न चले / अटके / बहुत धीमा हो तो अपने-आप Android"):
 * कौन-सी पहचान चले - सिर्फ़ फ़ैसला (Android import नहीं -> JVM test [SttRouterTest]).
 */
class SttRouter {
    enum class Engine { WHISPER, ANDROID, NONE }

    data class Pick(val engine: Engine, val notice: String)

    /** इस बातचीत में Whisper कितनी बार धीमा / नाकाम रहा (लगातार) */
    var strikes = 0; private set
    /** इस बातचीत के लिए Whisper छोड़ दिया (2 बार लगातार धीमा / नाकाम) */
    var whisperOff = false; private set
    var offWhy = ""; private set

    fun pick(prefWhisper: Boolean, whisperReady: Boolean, whyNot: String, androidOk: Boolean): Pick {
        val wantW = prefWhisper && whisperReady && !whisperOff
        return when {
            wantW -> Pick(Engine.WHISPER, "")
            androidOk -> Pick(Engine.ANDROID, when {
                !prefWhisper -> ""
                whisperOff -> "Whisper $offWhy - अभी Android की पहचान से सुन रहा हूँ"
                !whisperReady -> "${whyNot.ifBlank { "Whisper तैयार नहीं" }} - तब तक Android की पहचान"
                else -> ""
            })
            prefWhisper && whisperReady -> Pick(Engine.WHISPER, "")            // Android नहीं, Whisper फिर से आज़माओ
            else -> Pick(Engine.NONE, if (prefWhisper) "${whyNot.ifBlank { "Whisper तैयार नहीं" }}, और इस फ़ोन में Android की पहचान (Android 13+) भी नहीं"
                else "इस फ़ोन में Android की पहचान (Android 13+) नहीं - Settings में Whisper चुनकर मॉडल डाउनलोड करें")
        }
    }

    /** Whisper का नतीजा: true = इस बातचीत में अब Android पर (पहली बार) */
    fun whisperResult(ok: Boolean, slow: Boolean): Boolean {
        if (ok && !slow) { strikes = 0; return false }
        strikes++
        if (strikes >= MAX_STRIKES && !whisperOff) {
            whisperOff = true
            offWhy = if (slow) "इस फ़ोन पर बहुत धीमा चल रहा है" else "बार-बार नाकाम रहा"
            return true
        }
        return false
    }

    fun reset() { strikes = 0; whisperOff = false; offWhy = "" }

    companion object {
        const val MAX_STRIKES = 2
        /** Whisper कितनी देर तक: आवाज़ का 1.5 गुना, कम से कम 6 s, ज़्यादा से ज़्यादा 15 s */
        fun timeoutMs(audioMs: Long): Long = (audioMs * 3 / 2).coerceIn(6_000L, 15_000L)
        /** जवाब आया पर आवाज़ से बहुत ज़्यादा समय लगा = "धीमा" (अगली बार से Android की ओर गिनती) */
        fun slow(audioMs: Long, tookMs: Long): Boolean = tookMs > maxOf(4_000L, audioMs * 2)
    }
}
