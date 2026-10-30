package com.dk.assistant

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * v4.x: जवाब वाले बुलबुले में "DK लिख रही है" का एनीमेशन - पहले जैसी 7 बार (डिफ़ॉल्ट) + 5 नए डॉट वाले.
 * हर डॉट = (x, y, त्रिज्या, चमक) - x/y/त्रिज्या छोटे चौकोर के हिस्से में (-0.5..0.5), चमक 0..1.
 * Android import नहीं - JVM test से जाँच; DKWorkingIndicator इन्हीं डॉट को बनाता है.
 */
object TypingStyles {
    const val BARS = "bars"

    val STYLES = listOf(
        BARS to "DK तरंग (पहले जैसी 7 बार)",
        "bounce" to "उछलते डॉट",
        "wave" to "डॉट लहर",
        "pulse" to "धड़कते डॉट",
        "orbit" to "घूमता डॉट-छल्ला",
        "matrix" to "डॉट-ग्रिड स्कैन",
    )

    fun valid(s: String?): String = if (s != null && STYLES.any { it.first == s }) s else BARS

    /** ज़्यादा से ज़्यादा डॉट (out का आकार = MAX * 4) */
    const val MAX = 16

    /**
     * [phase] = चलता समय (रेडियन जैसा, हालत के हिसाब से तेज़/धीमा), [amp] = ज़ोर 0..1.
     * out में डॉट भरता है, लौटाता है कितने डॉट. BARS के लिए 0 (वह पुराना तरीका ही बनाता है).
     */
    fun dots(style: String, phase: Double, amp: Float, out: FloatArray): Int {
        val p = phase.toFloat()
        val k = 0.55f + 0.45f * amp.coerceIn(0f, 1f)
        var n = 0
        fun put(x: Float, y: Float, r: Float, a: Float) {
            if (n >= MAX) return
            out[n * 4] = x; out[n * 4 + 1] = y; out[n * 4 + 2] = r; out[n * 4 + 3] = a.coerceIn(0f, 1f); n++
        }
        when (style) {
            "bounce" -> for (i in 0 until 3) {
                val up = sin(p * 0.75f - i * 0.95f).coerceAtLeast(0f)
                put((i - 1) * 0.25f, -0.17f * up * k, 0.075f, 0.55f + 0.45f * up)
            }
            "wave" -> for (i in 0 until 7) {
                val v = sin(p * 0.65f + i * 0.85f)
                put((i - 3) * 0.12f, 0.17f * v * k, 0.042f + 0.012f * (v * 0.5f + 0.5f), 0.4f + 0.6f * (v * 0.5f + 0.5f))
            }
            "pulse" -> for (i in 0 until 3) {
                val b = sin(p * 0.7f - i * 1.1f).coerceAtLeast(0f)
                put((i - 1) * 0.25f, 0f, 0.045f + 0.05f * b * k, 0.35f + 0.65f * b)
            }
            "orbit" -> {
                val head = p * 0.55f
                for (i in 0 until 9) {
                    val a = head - i * 0.42f
                    val f = 1f - i / 9f
                    put(cos(a) * 0.3f, sin(a) * 0.3f, 0.025f + 0.045f * f, 0.15f + 0.85f * f)
                }
                put(0f, 0f, 0.05f + 0.02f * sin(p * 0.9f), 0.7f)
            }
            "matrix" -> {
                val scan = (p * 0.35f) % 4f
                for (r in 0 until 4) for (c in 0 until 4) {
                    var d = c - scan; if (d < -2f) d += 4f; if (d > 2f) d -= 4f
                    val g = exp(-d * d * 1.6f) * (0.7f + 0.3f * sin(p + r * 1.7f + c))
                    put((c - 1.5f) * 0.2f, (r - 1.5f) * 0.2f, 0.035f + 0.02f * g, 0.18f + 0.82f * g * k)
                }
            }
        }
        return n
    }
}
