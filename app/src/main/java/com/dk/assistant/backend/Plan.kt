package com.dk.assistant.backend

import android.content.Context
import com.dk.assistant.Prefs

/**
 * 18 नवंबर 2026 (मालिक की शर्तें): कौन क्या इस्तेमाल कर सकता है - ऐप में सिर्फ़ यही एक जगह.
 *
 *  - मुफ़्त (बिना लाइसेंस): अपनी Gemini key (ऑल-राउंडर) से लिखकर चैट जितनी चाहे; आवाज़ से बात रोज़ 10 मिनट
 *    ([VoiceGate] - समय सर्वर गिनता है). डीप रिसर्च / ऑटोनोमस / कोडिंग / DK कंप्यूटर नहीं (आवाज़ वाले 10 मिनट में
 *    DK लाइव के फ़ोन वाले काम और खोज चलते हैं). Settings में सिर्फ़ एक (ऑल-राउंडर) key.
 *  - बेसिक: चैट, बिना सीमा आवाज़, खोज, डीप रिसर्च. Settings में सिर्फ़ एक key.
 *  - प्रीमियम: सब कुछ + अलग-अलग keys वाले हिस्से.
 *  - मालिक (Google लॉगिन, backend ने माना): सब कुछ, कोई लाइसेंस नहीं.
 *
 * DK का सर्वर इस build में जुड़ा ही नहीं = पहले जैसा (कोई रोक नहीं) - वरना बिना सर्वर वाला ऐप बेकार हो जाता.
 * असली जाँच सर्वर पर है; यहाँ सिर्फ़ याद रखा स्तर (आख़िरी /license/status या /voice/grant से).
 */
object Plan {
    enum class Level(val n: Int) { FREE(0), BASIC(1), PREMIUM(2), OWNER(3) }

    enum class Work(val hi: String, val need: Level) {
        RESEARCH("डीप रिसर्च", Level.BASIC),
        CODING("कोडिंग एजेंट", Level.PREMIUM),
        COMPUTER("DK कंप्यूटर", Level.PREMIUM),
        WEBSITE("वेबसाइट बनाना", Level.PREMIUM),
        STORY("स्टोरी मोड", Level.PREMIUM),
        AUTO("ऑटोनोमस मोड", Level.PREMIUM),
    }

    private const val LEVEL = "plan_level"
    private const val UNTIL = "plan_until"
    private const val SERVER_AI = "plan_server_ai"

    /** test बदल सके */
    @Volatile var override: Level? = null

    fun parse(s: String?): Level = when (s?.trim()?.lowercase()) {
        "owner" -> Level.OWNER; "premium" -> Level.PREMIUM; "basic" -> Level.BASIC; else -> Level.FREE
    }

    /** याद रखा स्तर + मियाद -> अभी का स्तर (बिना Android, test के लिए). until 0 = कोई मियाद नहीं */
    fun effective(saved: Level, until: Long, now: Long): Level =
        if (saved == Level.BASIC || saved == Level.PREMIUM) { if (until in 1..now) Level.FREE else saved } else saved

    fun level(c: Context, now: Long = System.currentTimeMillis()): Level {
        override?.let { return it }
        if (!BackendConfig.ready()) return Level.OWNER
        if (BackendSession.owner(c)) return Level.OWNER
        return effective(parse(Prefs.str(c, LEVEL, "free")), Prefs.long(c, UNTIL, 0L), now)
    }

    /** सर्वर से आया स्तर याद रखो */
    fun note(c: Context, level: String, expiresAt: Long) {
        Prefs.edit(c) { putString(LEVEL, parse(level).name.lowercase()); putLong(UNTIL, expiresAt.coerceAtLeast(0L)) }
    }

    /**
     * 21 नवंबर 2026 (मालिक: "ऐप में कोई key न हो तो भी DK backend से जवाब दे"): सर्वर (/license/status का
     * `server_ai`) बताता है कि मुफ़्त वाले को भी सर्वर की AI मिलती है. पहली बार (अभी पूछा नहीं) = हाँ, ताकि
     * नया ऐप बिना key भी खाली न लौटे.
     */
    fun serverAi(c: Context): Boolean = Prefs.bool(c, SERVER_AI, true)

    fun noteServerAi(c: Context, on: Boolean) {
        if (serverAi(c) != on) Prefs.edit(c) { putBoolean(SERVER_AI, on) }
    }

    /** सिर्फ़ स्तर (बिना मियाद, जैसे /voice/grant से) - बदला हो तभी लिखो; मियाद अगली /license/status से */
    fun noteLevel(c: Context, level: String) {
        val l = parse(level)
        if (Prefs.str(c, LEVEL, "free") != l.name.lowercase()) Prefs.edit(c) { putString(LEVEL, l.name.lowercase()); putLong(UNTIL, 0L) }
    }

    fun allowed(have: Level, w: Work, voiceActive: Boolean = false): Boolean =
        have.n >= w.need.n || (w == Work.RESEARCH && voiceActive)

    /** अलग-अलग keys वाले हिस्से (DK लाइव / चैट / कोडिंग / कंप्यूटर की अपनी key) */
    fun separateKeys(have: Level): Boolean = have.n >= Level.PREMIUM.n
    fun separateKeys(c: Context): Boolean = separateKeys(level(c))

    fun message(w: Work): String =
        if (w.need == Level.BASIC) "${w.hi} लाइसेंस के साथ मिलती है। Settings → लाइसेंस की में की डालें — की न हो तो वहीं \"लाइसेंस की पाएँ\" दबाएँ।"
        else "${w.hi} प्रीमियम लाइसेंस के साथ मिलता है। Settings → लाइसेंस की में की डालें — की न हो तो वहीं \"लाइसेंस की पाएँ\" दबाएँ।"

    /** DK लाइव के मॉडल के लिए: यही बोलो, "हो गया" मत बोलो */
    fun liveNote(w: Work): String =
        "not_started_license: the user's plan does not include ${w.name}. Do NOT say it is done or started. " +
            "Tell the user in their language exactly: \"${message(w)}\""

    /** रोकना हो तो संदेश, वरना null */
    fun check(c: Context, w: Work, voiceActive: Boolean = VoiceGate.active()): String? =
        if (allowed(level(c), w, voiceActive)) null else message(w)
}
