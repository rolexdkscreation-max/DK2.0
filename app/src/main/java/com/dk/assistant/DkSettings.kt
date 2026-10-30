package com.dk.assistant

/**
 * v5 (मालिक): "DK के अंदर जितनी भी सेटिंग है, वह सब DK को पता हो और DK उन्हें ऑन-ऑफ कर सके.
 * ऑन-ऑफ के लिए यूज़र बोले तो DK लाइव दो बार पक्का करे - पहले 'ठीक है बॉस', फिर 'सर, क्या सच में आप यह ऑन/ऑफ करना चाहते हैं?'
 * हाँ बोलने पर ही बदले."
 *
 * Pure logic (कोई Android import नहीं) -> JVM unit test. फ़ोन पर लागू करना: [DkSettingsApply].
 */
object DkSettings {
    /**
     * @param pref SharedPreferences की boolean key, @param def उसका default
     * @param inverted true = pref उल्टा है (जैसे barge_hold = "रुककर सुने" बंद)
     * @param alsoOn ऑन करते समय साथ में ये keys भी true / [alsoOff] ऑफ करते समय false
     */
    data class Item(
        val id: String, val name: String, val section: String, val pref: String, val def: Boolean,
        val words: List<String>, val inverted: Boolean = false,
        val alsoOn: List<Pair<String, Boolean>> = emptyList(), val alsoOff: List<Pair<String, Boolean>> = emptyList(),
    )

    val ITEMS: List<Item> = listOf(
        Item("wake", "हमेशा सुनो (जगाने वाला शब्द)", "voice", "wake_mode", false,
            listOf("हमेशा सुनो", "wake", "वेक", "जगाने", "always listen", "हमेशा सुनना"), alsoOn = listOf("mic_user_off" to false)),
        Item("dk_only", "सिर्फ़ DK बोलने पर सुनो", "voice", "dk_only_listen", false,
            listOf("सिर्फ़ dk", "सिर्फ dk", "dk बोलने पर", "डीके बोलने पर", "dk only")),
        Item("calls", "आने वाली कॉल बोलकर बताओ", "voice", "announce_calls", true,
            listOf("कॉल बोलकर", "कॉल बताओ", "call announce", "कॉल अनाउंस", "आने वाली कॉल")),
        Item("screen", "स्क्रीन देखना / शेयर करना", "access", "screenshot", false,
            listOf("स्क्रीन देखना", "स्क्रीन शेयर", "screen share", "screenshot", "स्क्रीनशॉट", "स्क्रीन"),
            alsoOn = listOf(ScreenGuard.USER_OK_KEY to true), alsoOff = listOf(ScreenGuard.USER_OK_KEY to false)),
        Item("web", "इंटरनेट (Google Search) से जवाब", "brain", "web_answers", true,
            listOf("इंटरनेट", "गूगल सर्च", "google search", "web search", "वेब सर्च", "ऑनलाइन जवाब")),
        Item("auto_update", "ऑटो अपडेट", "update", "auto_update", true,
            listOf("ऑटो अपडेट", "auto update", "अपडेट", "update")),
        Item("duck", "बोलते समय पीछे का गाना धीमा करो", "voice", "duck_media", true,
            listOf("गाना धीमा", "गाना धीरे", "म्यूज़िक धीमा", "duck", "डक", "वीडियो धीमा")),
        Item("far_voice", "दूर की आवाज़ अनसुनी करो", "voice", "owner_far_on", true,
            listOf("दूर की आवाज़", "दूर की आवाज", "far voice", "दूर वाली आवाज़")),
        Item("barge", "जवाब के बीच बोलूँ तो DK रुककर सुने", "voice", "barge_hold", false,
            listOf("बीच में रोक", "जवाब के बीच", "barge", "बार्ज", "रुककर सुने", "बीच में बोलूँ"), inverted = true),
        Item("wa_send", "WhatsApp मैसेज बिना पूछे तुरंत भेजो", "voice", "wa_auto_send", false,
            listOf("बिना पूछे भेजो", "auto send", "ऑटो सेंड", "तुरंत भेजो", "whatsapp", "व्हाट्सएप")),
        Item("friend", "दोस्त वाला अंदाज़ + पिछली बातचीत की याद", "profile", "friend_mode", true,
            listOf("दोस्त वाला", "फ्रेंड मोड", "friend mode", "दोस्त मोड")),
        Item("opener", "ऐप खोलते ही DK खुद बात शुरू करे", "profile", "friend_opener", true,
            listOf("खुद बात शुरू", "अभिवादन", "greeting", "ग्रीटिंग", "हालचाल")),
        Item("glow", "सुनते/बोलते समय किनारे की चमक", "orb", "edge_glow", true,
            listOf("चमक", "glow", "ग्लो", "किनारे की चमक", "edge")),
        Item("memory", "लोकल याददाश्त", "profile", "local_memory_on", true,
            listOf("याददाश्त", "मेमोरी", "memory", "याद रखना")),
        Item("computer", "DK कंप्यूटर", "computer", "pc_on", true,
            listOf("dk कंप्यूटर", "डीके कंप्यूटर", "dk computer", "कंप्यूटर")),
        Item("computer_ask", "DK कंप्यूटर फ़ोन के काम से पहले पूछे", "computer", "pc_confirm", true,
            listOf("कंप्यूटर पूछे", "पहले पूछो", "computer confirm", "पेमेंट से पहले")),
        Item("coder", "कोडिंग एजेंट", "coder", "coder_on", false,
            listOf("कोडिंग एजेंट", "coding agent", "कोडर", "coder", "कोडिंग")),
        Item("coder_push", "कोडिंग एजेंट बिना पूछे push करे", "coder", "coder_autopush", false,
            listOf("ऑटो पुश", "auto push", "बिना पूछे push", "पुश")),
        Item("coder_fix", "Push के बाद tests + build जाँचो और खुद ठीक करो", "coder", "coder_buildfix", true,
            listOf("बिल्ड फिक्स", "build fix", "टेस्ट जाँचो", "build जाँचो")),
        Item("coder_team", "मल्टी-एजेंट टीम", "coder", "coder_team", true,
            listOf("मल्टी एजेंट", "multi agent", "एजेंट टीम", "टीम")),
    )

    private fun n(s: String) = s.lowercase().replace("़", "").replace("ॉ", "ो").replace(Regex("\\s+"), " ").trim()

    /** यूज़र के शब्दों से सबसे मिलती सेटिंग (id, नाम या आम शब्द) */
    fun find(q: String): Item? {
        val t = n(q)
        if (t.isBlank()) return null
        ITEMS.firstOrNull { it.id == t }?.let { return it }
        var best: Item? = null; var bestLen = 0
        for (it in ITEMS) for (w in listOf(it.name) + it.words) {
            val x = n(w)
            if (x.isNotBlank() && t.contains(x) && x.length > bestLen) { best = it; bestLen = x.length }
        }
        if (best != null) return best
        return ITEMS.firstOrNull { n(it.name).contains(t) }
    }

    /** pref से "ऑन" है? (inverted का ध्यान) */
    fun isOn(it: Item, prefValue: Boolean): Boolean = if (it.inverted) !prefValue else prefValue
    /** "ऑन/ऑफ" करने पर pref में क्या लिखें */
    fun prefFor(it: Item, on: Boolean): Boolean = if (it.inverted) !on else on

    /** दो बार पक्का करने का इंतज़ार: (id, on, कब पूछा) */
    data class Pending(val id: String, val on: Boolean, val at: Long)
    @Volatile var pending: Pending? = null
    const val CONFIRM_WINDOW_MS = 2 * 60_000L

    fun onOff(on: Boolean) = if (on) "ऑन" else "ऑफ"

    /** पहली बार माँगा = अभी मत बदलो, "ठीक है बॉस" + "सर, क्या सच में…?" पूछो */
    fun ask(it: Item, on: Boolean, now: Long): String {
        pending = Pending(it.id, on, now)
        return "confirm_needed: DO NOT change it yet and do NOT say it is done. First say \"ठीक है बॉस।\" and then ask exactly once: " +
            "\"सर, क्या आप सच में '${it.name}' ${onOff(on)} करना चाहते हैं?\" Wait for his answer. Only if he clearly says yes (हाँ / हाँ कर दो / yes), " +
            "call dk_settings again with action=set, the same setting, on=$on and confirmed=true. If he says no, leave it and tell him it was not changed."
    }

    /** दूसरी बार (confirmed=true) - सच में पहले पूछा गया था, वही सेटिंग, वही ऑन/ऑफ, 2 मिनट के अंदर? */
    fun confirmOk(it: Item, on: Boolean, now: Long): Boolean {
        val p = pending ?: return false
        val ok = p.id == it.id && p.on == on && now - p.at in 0..CONFIRM_WINDOW_MS
        if (ok) pending = null
        return ok
    }

    /** DK को सारी सेटिंग्स पता हों: हर ऑन-ऑफ वाली सेटिंग अभी की हालत के साथ + सारे हिस्सों के नाम */
    fun catalog(state: (Item) -> Boolean): String = buildString {
        appendLine("DK settings you can switch ON/OFF (always with the two-step confirmation):")
        ITEMS.forEach { appendLine("- ${it.name} [${it.id}]: ${if (state(it)) "ON" else "OFF"}") }
        appendLine("Other parts of Settings you can OPEN for the user (action=open): " + SettingsSearch.SECTIONS.values.joinToString(", ") + ".")
        append("API keys, voice choice and model choice are changed by the user himself on the Settings screen - open that part for him.")
    }
}
