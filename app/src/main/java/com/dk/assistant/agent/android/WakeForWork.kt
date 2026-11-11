package com.dk.assistant.agent.android

import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.unlock
import kotlinx.coroutines.delay

/**
 * नवंबर 2026 (मालिक: "जाओ GPT / Claude से बात करो - तो ऐप खोले, लिखे, भेजे; और DK काम कर रहा हो तब स्क्रीन बंद न हो").
 * असली वजह: DK लाइव से बात अक्सर स्क्रीन बंद रहते होती है - तब AI ऐप वाला काम अंधेरी / लॉक स्क्रीन पर ऐप खोलने की कोशिश
 * करता था (न ऐप सामने आता, न लिखना-भेजना). अब काम शुरू होने से पहले स्क्रीन जगाओ + (PIN सेव हो तो) खोलो; लॉक रहे तो
 * मालिक के अनलॉक करने तक रुको. काम के बीच स्क्रीन जगी रहना WorkAwake (FLAG_KEEP_SCREEN_ON वाली पट्टी) करता है.
 */
object WakeForWork {
    const val WAIT_UNLOCK_MS = 45_000L

    /** स्क्रीन जगानी / खोलनी है? (शुद्ध - JVM test) */
    fun needWake(interactive: Boolean, locked: Boolean): Boolean = !interactive || locked

    /** true = स्क्रीन जगी और खुली - ऐप में काम हो सकता है */
    suspend fun ensure(svc: DKAccessibilityService, waitMs: Long = WAIT_UNLOCK_MS, step: (String) -> Unit = {}): Boolean {
        val pm = svc.getSystemService(android.os.PowerManager::class.java)
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        if (!needWake(pm?.isInteractive != false, km?.isKeyguardLocked == true)) return true
        step("स्क्रीन जगा रही हूँ")
        try { DKLog.d("WakeForWork: " + svc.unlock()) } catch (e: Exception) { DKLog.d("WakeForWork: स्क्रीन नहीं जगी ${e.message}") }
        if (km?.isKeyguardLocked == true) {
            step("फ़ोन लॉक है - कृपया अनलॉक करें, फिर मैं आपके सामने ऐप खोलूँगी")
            val until = System.currentTimeMillis() + waitMs
            while (km.isKeyguardLocked && System.currentTimeMillis() < until) delay(500)
        }
        return km?.isKeyguardLocked != true && pm?.isInteractive != false
    }
}
