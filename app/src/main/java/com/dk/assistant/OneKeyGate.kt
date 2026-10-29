package com.dk.assistant

import android.content.Context
import android.content.Intent

/**
 * v3.6 (owner का नियम): "एक key से सब" = एक Gemini key पर सिर्फ़ बातचीत.
 *
 * चलेगा: DK लाइव में बात, चैट, सुनना + "आप:" लिखना (उसी Gemini key से), इंटरनेट खोज, फ़ोन के काम
 *        (ऐप खोलना, WhatsApp, कॉल, अलार्म, ऑटोनॉमस फ़ोन काम).
 * नहीं चलेगा: कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर, वेबसाइट बनाने वाला (Forge).
 * इनके लिए DK साफ़ बोले: "एक key बंद करके अलग-अलग keys डालिए" - झूठा "हो गया" नहीं.
 * एक key बंद करते ही Settings में अलग-अलग keys वाले सारे options वैसे ही दिखते हैं जैसे पहले.
 */
object OneKeyGate {
    enum class Work(val hi: String) { CODING("कोडिंग एजेंट"), STORY("स्टोरी मोड"), COMPUTER("DK कंप्यूटर"), WEBSITE("वेबसाइट बनाना") }

    /** इन tools को एक key पर रोकना है (DK लाइव) */
    val LIVE_TOOLS: Map<String, Work> = mapOf(
        "coding_task" to Work.CODING, "story_task" to Work.STORY,
        "computer_task" to Work.COMPUTER, "build_website" to Work.WEBSITE,
    )

    fun blocked(oneKeyOn: Boolean, w: Work?): Boolean = oneKeyOn && w != null
    fun blocked(c: Context, w: Work): Boolean = blocked(Prefs.oneKeyOn(c), w)

    fun message(w: Work): String =
        "${w.hi} एक key पर नहीं चलता — एक key से सिर्फ़ बातचीत, चैट, खोज और फ़ोन के काम होते हैं। " +
            "Settings → AI दिमाग में \"एक key से सब\" बंद करके अलग-अलग keys डालिए, फिर बोलिए।"

    /** DK लाइव के मॉडल के लिए tool का जवाब: यही बोलो, "हो गया" मत बोलो */
    fun liveNote(w: Work): String =
        "not_started_one_key_mode: The owner uses ONE Gemini key, which is only for talking, chat, search and phone tasks. " +
            "${w.name} cannot run on it. Do NOT say it is done or started. Tell the user in their language exactly: \"${message(w)}\" " +
            "The Settings screen has been opened for them."

    /** Settings → AI दिमाग खोलो (वहीं "एक key से सब" का switch है) */
    fun openSettings(c: Context) {
        try {
            c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { DKLog.d("Settings नहीं खुली: ${e.message}") }
    }

    /** UI/सर्विस: रोकना हो तो संदेश, वरना null */
    fun check(c: Context, w: Work): String? = if (blocked(c, w)) message(w).also { DKLog.d("एक key मोड: ${w.hi} रोका") } else null
}
