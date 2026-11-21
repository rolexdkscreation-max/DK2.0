package com.dk.assistant.stt

/**
 * 18 नवंबर 2026 (मालिक: "Whisper चुनूँ या Android; Whisper न चले / अटके / बहुत धीमा हो तो अपने-आप Android"):
 * कौन-सी पहचान चले - सिर्फ़ फ़ैसला (Android import नहीं -> JVM test [SttRouterTest]).
 * 21 नवंबर 2026: Whisper हटा - उसकी जगह फ़ोन पर Sherpa-ONNX ([SherpaStt]). इंजन सिर्फ़ दो: Sherpa-ONNX / Android.
 */
class SttRouter {
    enum class Engine { SHERPA, ANDROID, NONE }

    data class Pick(val engine: Engine, val notice: String)

    /** इस बातचीत में Sherpa-ONNX कितनी बार धीमा / नाकाम रहा (लगातार) */
    var strikes = 0; private set
    /** इस बातचीत के लिए Sherpa-ONNX छोड़ दिया (2 बार लगातार धीमा / नाकाम) */
    var sherpaOff = false; private set
    var offWhy = ""; private set

    fun pick(prefSherpa: Boolean, sherpaReady: Boolean, whyNot: String, androidOk: Boolean): Pick {
        val wantS = prefSherpa && sherpaReady && !sherpaOff
        return when {
            wantS -> Pick(Engine.SHERPA, "")
            androidOk -> Pick(Engine.ANDROID, when {
                !prefSherpa -> ""
                sherpaOff -> "$NAME $offWhy - अभी Android की पहचान से सुन रहा हूँ"
                !sherpaReady -> "${whyNot.ifBlank { "$NAME तैयार नहीं" }} - तब तक Android की पहचान"
                else -> ""
            })
            prefSherpa && sherpaReady -> Pick(Engine.SHERPA, "")            // Android नहीं, Sherpa-ONNX फिर से आज़माओ
            else -> Pick(Engine.NONE, if (prefSherpa) "${whyNot.ifBlank { "$NAME तैयार नहीं" }}, और इस फ़ोन में Android की पहचान (Android 13+) भी नहीं"
                else "इस फ़ोन में Android की पहचान (Android 13+) नहीं - Settings में $NAME चुनकर मॉडल डाउनलोड करें")
        }
    }

    /** Sherpa-ONNX का नतीजा: true = इस बातचीत में अब Android पर (पहली बार) */
    fun sherpaResult(ok: Boolean, slow: Boolean): Boolean {
        if (ok && !slow) { strikes = 0; return false }
        strikes++
        if (strikes >= MAX_STRIKES && !sherpaOff) {
            sherpaOff = true
            offWhy = if (slow) "इस फ़ोन पर बहुत धीमा चल रहा है" else "बार-बार नाकाम रहा"
            return true
        }
        return false
    }

    fun reset() { strikes = 0; sherpaOff = false; offWhy = "" }

    companion object {
        const val MAX_STRIKES = 2
        const val NAME = "IndicConformer"
        /** पहचान कितनी देर तक: आवाज़ का 1.5 गुना, कम से कम 6 s, ज़्यादा से ज़्यादा 15 s */
        fun timeoutMs(audioMs: Long): Long = (audioMs * 3 / 2).coerceIn(6_000L, 15_000L)
        /** जवाब आया पर आवाज़ से बहुत ज़्यादा समय लगा = "धीमा" (अगली बार से Android की ओर गिनती) */
        fun slow(audioMs: Long, tookMs: Long): Boolean = tookMs > maxOf(4_000L, audioMs * 2)
    }
}
