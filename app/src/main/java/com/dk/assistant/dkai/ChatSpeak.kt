package com.dk.assistant.dkai

import android.content.Context
import kotlinx.coroutines.launch

/**
 * 23 नवंबर 2026 (मालिक): Chat का "बोलकर भी" - पहले वाला वही toggle (बाय डिफ़ॉल्ट बंद). चालू = लिखकर आया जवाब फ़ोन की
 * ऑफ़लाइन आवाज़ में भी ([com.dk.assistant.PhoneVoice.say]: पहले DK की ऑफ़लाइन आवाज़, न हो तो Android TTS).
 * ChatActivity और होम का Chat टैब एक ही चाबी ("chat_speak") पढ़ते हैं - एक जगह बदला = दोनों जगह वही.
 */
object ChatSpeak {
    const val KEY = "chat_speak"
    const val LABEL = "बोलकर भी"

    fun on(c: Context): Boolean = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(c: Context, v: Boolean) {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()
        if (!v) com.dk.assistant.PhoneVoice.stop()           // बंद किया = चल रहा बोलना भी रुके
    }

    /** बोलने लायक पाठ: इमेज / खाली / "…" नहीं; markdown के निशान हटाकर */
    fun speakable(t: String): String? {
        val s = t.replace(Regex("[*#`_>]"), "").trim()
        if (s.isBlank() || s == "…" || com.dk.assistant.FluxImage.parse(t) != null) return null
        return s
    }

    /** toggle चालू हो तो जवाब बोलो (पीछे, UI नहीं रुकता) */
    fun sayIfOn(c: Context, reply: String) {
        if (!on(c)) return
        val s = speakable(reply) ?: return
        val app = c.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try { com.dk.assistant.PhoneVoice.say(app, s) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        }
    }
}
