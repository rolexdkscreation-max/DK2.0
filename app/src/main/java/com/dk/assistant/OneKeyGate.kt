package com.dk.assistant

import android.content.Context
import android.content.Intent

/**
 * "एक key से सब" (ऑल-राउंडर).
 *
 * नवंबर 2026 (मालिक): "एक key डालेगा तो सारे काम होंगे - जितने भी काम DK में हैं, सारे". पहले (v3.6) एक key पर
 * कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर और वेबसाइट बिल्डर रोके जाते थे. अब वे भी उसी एक Gemini key से चलते हैं
 * (Prefs.scopeKey / Prefs.key / Prefs.forgeKeys एक-key मोड में वही key देते हैं).
 * रोक अब सिर्फ़ तब: एक-key मोड चालू है पर key डाली ही नहीं - तब DK साफ़ बोले "key डालिए", झूठा "हो गया" नहीं.
 * एक key बंद करते ही Settings में अलग-अलग keys वाले सारे options वैसे ही दिखते हैं जैसे पहले.
 */
object OneKeyGate {
    enum class Work(val hi: String) { CODING("कोडिंग एजेंट"), STORY("स्टोरी मोड"), COMPUTER("DK कंप्यूटर"), WEBSITE("वेबसाइट बनाना") }

    /** एजेंट वाले tools (DK लाइव) - एक-key मोड में key न हो तो यही रुकते हैं */
    val LIVE_TOOLS: Map<String, Work> = mapOf(
        "coding_task" to Work.CODING, "story_task" to Work.STORY,
        "computer_task" to Work.COMPUTER, "build_website" to Work.WEBSITE,
    )

    /** @param keyBlank एक-key मोड की key खाली है? (key डली हो = सब चलता है) */
    fun blocked(oneKeyOn: Boolean, w: Work?, keyBlank: Boolean): Boolean = oneKeyOn && keyBlank && w != null
    fun blocked(c: Context, w: Work): Boolean = blocked(Prefs.oneKeyOn(c), w, Prefs.oneKey(c).isBlank())

    fun message(w: Work): String =
        "${w.hi} के लिए अभी कोई key नहीं डली। Settings → AI दिमाग → \"एक key से सब\" में अपनी एक Gemini key पेस्ट करें — " +
            "फिर बात, चैट, खोज, फ़ोन के काम, कोडिंग, स्टोरी, DK कंप्यूटर और वेबसाइट सब उसी एक key से चलेंगे " +
            "(या \"एक key से सब\" बंद करके अलग-अलग keys डालें)।"

    /** DK लाइव के मॉडल के लिए tool का जवाब: यही बोलो, "हो गया" मत बोलो */
    fun liveNote(w: Work): String =
        "not_started_no_key: ONE-KEY mode is on but the owner has not pasted the key yet, so ${w.name} cannot run. " +
            "Do NOT say it is done or started. Tell the user in their language exactly: \"${message(w)}\" " +
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
