package com.dk.assistant

import java.io.File

/**
 * SettingsActivity का पूरा source एक साथ (source-check वाले tests के लिए)।
 *
 * SettingsActivity.kt अब कई फ़ाइलों में बँटा है (AI दिमाग, आवाज़, सिस्टम, रूप, कोडर, छोटे हिस्से)। पुराने tests जो
 * SettingsActivity.kt में कोई लाइन ढूँढते थे, वे अब सारे हिस्से जोड़कर वही लाइन ढूँढते हैं —
 * assertion वही, सिर्फ़ पढ़ने की जगह बदली। नया हिस्सा बने तो PARTS में जोड़ें।
 */
object SetSrc {
    val PARTS = listOf(
        "SettingsActivity.kt",
        "SettingsBrain.kt",
        "SettingsVoice.kt",
        "SettingsSystem.kt",
        "SettingsLook.kt",
        "SettingsCoder.kt",
        "SettingsWidgets.kt",
        "SettingsPhoneLock.kt",
        "SettingsSocial.kt",
        "SettingsReels.kt",
    )

    private fun dir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant"), File(d, "app/src/main/java/com/dk/assistant"))
                .firstOrNull { File(it, "SettingsActivity.kt").exists() }?.let { return it }
            d = d.parentFile
        }
        error("SettingsActivity.kt नहीं मिला")
    }

    /** सारे हिस्से, एक के बाद एक (हर हिस्सा मौजूद होना चाहिए) */
    fun all(): String {
        val d = dir()
        return PARTS.joinToString("\n") { File(d, it).also { f -> check(f.exists()) { "$it नहीं मिला" } }.readText() }
    }
}
