package com.dk.assistant.plus

import android.content.Context
import com.dk.assistant.QuickCommands
import org.json.JSONObject

/**
 * 15 नवंबर 2026: DK AI Plus की बोलकर चलने वाली कमांड (बिना AI, तुरंत):
 *  - "DK, activate SOS" / "SOS चालू करो" / "एसओएस भेजो"  -> इमरजेंसी SOS     ("SOS बंद" / "SOS रोको" -> रोकना)
 *  - "DK, turn on driving mode" / "ड्राइविंग मोड चालू"     -> ड्राइविंग मोड     ("... बंद" -> बंद)
 *  - "Touch Guard चालू" अंग्रेज़ी में भी -> पुराना टच मोड ([com.dk.assistant.GuardRules], वही 12 सेकंड वाला)
 * [parse] pure (JVM test - [PlusCommandsTest]); [run] Accessibility executor से.
 */
object PlusCommands {
    const val SOS = "plus_sos"
    const val DRIVING = "plus_driving"
    val TYPES = setOf(SOS, DRIVING)

    private fun a(type: String, on: Boolean) = JSONObject().put("type", type).put("on", on)

    fun parse(t0: String): QuickCommands.Quick? {
        val t = " " + t0.lowercase().trim() + " "
        fun has(vararg w: String) = w.any { t.contains(it) }
        val off = has("बंद", "रोको", "रोक दो", "stop", " off", "cancel", "कैंसल", "deactivate")
        val words = t.trim().split(Regex("\\s+")).size
        if (words > 9) return null
        if (has(" sos ", "एसओएस", "एस ओ एस", "s.o.s", "इमरजेंसी अलर्ट", "emergency alert")) {
            return if (off) QuickCommands.Quick(a(SOS, false), "SOS रोक रही हूँ")
            else if (has("activate", "चालू", "भेज", "send", "शुरू", "start", "on", "ऑन", "करो", "कर दो", " sos ")) QuickCommands.Quick(a(SOS, true), "")
            else null
        }
        if (has("driving mode", "ड्राइविंग मोड", "ड्राइव मोड", "drive mode", "गाड़ी मोड", "गाड़ी चला रहा")) {
            return if (off) QuickCommands.Quick(a(DRIVING, false), "ड्राइविंग मोड बंद")
            else QuickCommands.Quick(a(DRIVING, true), "ड्राइविंग मोड चालू")
        }
        return null
    }

    fun run(c: Context, a: JSONObject): String = when (a.optString("type")) {
        SOS -> if (a.optBoolean("on", true)) Sos.start(c) else Sos.stop()
        DRIVING -> DrivingMode.set(c, a.optBoolean("on", true))
        else -> "ERROR: unknown"
    }
}
