package com.dk.assistant

import java.io.File

/**
 * DK Live का पूरा source एक साथ (source-check वाले tests के लिए)।
 *
 * GeminiLive.kt अब कई फ़ाइलों में बँटा है (GeminiLiveTools.kt, GeminiLiveSetup.kt, GeminiLiveRace.kt,
 * GeminiLiveServer.kt, GeminiLiveQueue.kt, GeminiLiveMic.kt, GeminiLivePlayer.kt)। पुराने tests जो
 * GeminiLive.kt में कोई लाइन ढूँढते थे, वे अब सारे हिस्से जोड़कर वही लाइन ढूँढते हैं —
 * assertion वही, सिर्फ़ पढ़ने की जगह बदली। नया हिस्सा बने तो PARTS में जोड़ें।
 */
object LiveSrc {
    val PARTS = listOf(
        "GeminiLive.kt",
        "GeminiLiveTools.kt",
        "GeminiLiveSetup.kt",
        "GeminiLiveRace.kt",
        "GeminiLiveServer.kt",
        "GeminiLiveQueue.kt",
        "GeminiLiveMic.kt",
        "GeminiLivePlayer.kt",
        "GeminiLiveRecover.kt",
        "GeminiLiveCaption.kt",
        "GeminiLiveOwner.kt",
        "GeminiLiveTurnEnd.kt",
        "GeminiLiveActHold.kt",
        "GeminiLiveTurn.kt",
        "GeminiLiveChecks.kt",
        "GeminiLiveRotate.kt",
        "GeminiLiveRescue.kt",
    )

    private fun dir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant"), File(d, "app/src/main/java/com/dk/assistant"))
                .firstOrNull { File(it, "GeminiLive.kt").exists() }?.let { return it }
            d = d.parentFile
        }
        error("GeminiLive.kt नहीं मिला")
    }

    /** सारे हिस्से, एक के बाद एक (हर हिस्सा मौजूद होना चाहिए) */
    fun all(): String {
        val d = dir()
        return PARTS.joinToString("\n") { File(d, it).also { f -> check(f.exists()) { "$it नहीं मिला" } }.readText() }
    }
}
