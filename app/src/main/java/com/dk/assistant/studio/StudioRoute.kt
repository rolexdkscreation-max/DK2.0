package com.dk.assistant.studio

import android.content.Context
import com.dk.assistant.Prefs

/** ऐप स्टूडियो कब चले (कोडिंग एजेंट / DK कंप्यूटर / आवाज़) */
object StudioRoute {
    /** GitHub से काम नहीं हो सकता (token / रिपो नहीं) या user ने नॉर्मल मोड चुना = कोडिंग एजेंट फ़ोन पर बनाए */
    fun noGithub(c: Context): Boolean = noGithub(Prefs.ghToken(c), Prefs.ghRepo(c), Prefs.coderNormal(c))
    fun noGithub(token: String, repo: String, normal: Boolean): Boolean = normal || token.isBlank() || repo.isBlank()

    private val WEBSITE_ONLY = listOf("website", "web site", "webpage", "web page", "वेबसाइट", "वेब साइट", "वेब पेज", "landing page", "लैंडिंग पेज", "portfolio", "पोर्टफोलियो")
    private val GAME = listOf("game", "गेम", "खेल")
    private val APP = listOf("app", "apps", "application", "एप", "ऐप", "एप्प", "ऐप्प", "एप्लीकेशन", "एप्लिकेशन", "ऐप्लिकेशन", "pwa", "android", "एंड्रॉइड", "एंड्रॉयड", "flutter", "फ्लटर")

    /**
     * आवाज़ वाला रास्ता: सिर्फ़ "ऐप" वाली बात (वेबसाइट = Forge की 3D स्क्रीन, गेम = GameMaker - वो पहले जैसे).
     * "वेब ऐप" / "Android ऐप" = स्टूडियो.
     */
    fun voiceApp(text: String?): String? {
        val want = StudioCore.extract(text) ?: return null
        val low = " " + want.lowercase() + " "
        val words = low.split(Regex("[^\\p{L}\\p{M}\\p{N}']+")).filter { it.isNotBlank() }.toSet()
        if (APP.none { it in words } && !low.contains("web app") && !low.contains("वेब ऐप")) return null
        if (GAME.any { it in words }) return null
        if (WEBSITE_ONLY.any { low.contains(it) } && !low.contains("web app") && !low.contains("वेब ऐप") && APP.none { it in words }) return null
        return want
    }

    const val STARTED_CODER = "🧩 GitHub नहीं जुड़ा — इसलिए ऐप स्टूडियो में फ़ोन पर ही बना रहा हूँ। स्टूडियो की स्क्रीन पर बनते हुए देखिए; preview वहीं चलेगा और वहीं से बदलाव करवा सकते हैं।"
    const val STARTED_COMPUTER = "🧩 ऐप स्टूडियो में बना रहा हूँ — स्टूडियो की स्क्रीन पर हर फ़ाइल बनते ही preview दिखेगा; वहीं नीचे लिखकर / बोलकर बदलाव करवाइए।"
    const val STARTED_VOICE = "ठीक है, DK कंप्यूटर ऐप स्टूडियो में आपका ऐप बना रहा है। स्क्रीन पर बनते हुए दिखेगा, और तैयार होते ही बताऊँगी।"
}
