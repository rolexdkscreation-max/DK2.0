package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags

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
        /** 15 नवंबर 2026: pref कहाँ है - [STORE_MAIN] (Prefs), [STORE_FLAGS] (plus.FeatureFlags), [STORE_CUSTOM] (अपनी सुविधा का setter) */
        val store: String = STORE_MAIN,
    )

    const val STORE_MAIN = "main"
    const val STORE_FLAGS = "flags"
    const val STORE_CUSTOM = "custom"

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
        // नवंबर 2026 (Live जाँच): बिना रुके बातचीत की सेटिंग्स - बोलकर भी बदल सकें
        Item("call_speaker", "कॉल-mode स्पीकर (पक्का echo-cancel)", "voice", LiveSmooth.PREF_CALL_SPEAKER, false,
            listOf("कॉल मोड स्पीकर", "कॉल-mode", "call mode", "echo cancel", "इको कैंसल", "गूँज हटाओ")),
        Item("smart_vad", "स्मार्ट VAD (शोर को बात न माने)", "voice", LiveSmooth.PREF_SMART_VAD, true,
            listOf("स्मार्ट vad", "smart vad", "स्मार्ट वैड", "शोर को बात", "vad")),
        Item("slim_tools", "DK लाइव के हल्के tools", "voice", LiveSmooth.PREF_SLIM_TOOLS, true,
            listOf("हल्के tools", "हल्के टूल", "slim tools", "लाइट टूल")),
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
        Item("default_assistant", "डिफ़ॉल्ट असिस्टेंट", "system", "default_assistant", false,
            listOf("डिफ़ॉल्ट असिस्टेंट", "default assistant", "डिफ़ॉल्ट असिस्टेंट", "main assistant", "मुख्य असिस्टेंट")),
        // 15 नवंबर 2026 (मालिक: "DK के अंदर जो भी फीचर है, DK लाइव को पता हो और बोलकर चालू-बंद कर सके") - वही दो-बार-पक्का नियम
        Item("sos", "इमरजेंसी SOS सुविधा", "guard", FeatureFlags.SOS_ON, false,
            listOf("sos सुविधा", "sos सेटिंग", "sos फीचर", "sos feature", "एसओएस सुविधा", "एसओएस सेटिंग", "इमरजेंसी sos", "emergency sos"), store = STORE_FLAGS),
        Item("driving", "ड्राइविंग मोड", "access", FeatureFlags.DRIVING_MODE, false,
            listOf("ड्राइविंग मोड", "ड्राइविंग", "driving mode", "driving", "ड्राइव मोड", "गाड़ी चला"), store = STORE_FLAGS),
        Item("events", "ऑटो इवेंट ट्रिगर", "access", FeatureFlags.EVENTS_ON, false,
            listOf("इवेंट ट्रिगर", "ऑटो इवेंट", "event trigger", "इवेंट", "चार्जर लगने पर", "वाईफाई जुड़ने पर"), store = STORE_FLAGS),
        Item("ev_charger_on", "चार्जर लगा तो बताओ", "access", FeatureFlags.EV_CHARGER_ON, false,
            listOf("चार्जर लगा", "charger connected", "चार्जर लगने"), store = STORE_FLAGS),
        Item("ev_charger_off", "चार्जर हटा तो बताओ", "access", FeatureFlags.EV_CHARGER_OFF, false,
            listOf("चार्जर हटा", "चार्जर निकला", "charger disconnected"), store = STORE_FLAGS),
        Item("ev_wifi_on", "Wi-Fi जुड़ा तो बताओ", "access", FeatureFlags.EV_WIFI_ON, false,
            listOf("wifi जुड़ा", "वाईफाई जुड़ा", "wifi connected"), store = STORE_FLAGS),
        Item("ev_wifi_off", "Wi-Fi टूटा तो बताओ", "access", FeatureFlags.EV_WIFI_OFF, false,
            listOf("wifi टूटा", "वाईफाई टूटा", "wifi disconnected"), store = STORE_FLAGS),
        Item("ev_battery", "बैटरी सेवर बदला तो बताओ", "access", FeatureFlags.EV_BATTERY_SAVER, false,
            listOf("बैटरी सेवर बदला", "battery saver changed"), store = STORE_FLAGS),
        Item("boot", "फ़ोन चालू होते ही ट्रिगर फिर शुरू", "access", FeatureFlags.START_ON_BOOT, false,
            listOf("फ़ोन चालू होते", "फोन चालू होते", "start on boot", "boot", "बूट"), store = STORE_FLAGS),
        Item("termux", "Termux ऑटोमेशन", "coder", FeatureFlags.TERMUX, false,
            listOf("termux", "टर्मक्स", "टर्मुक्स", "टर्मिनल ऑटोमेशन"), store = STORE_FLAGS),
        Item("image_gen", "बोलकर तस्वीर बनाना (मुफ़्त API)", "computer", FeatureFlags.IMAGE_GEN, false,
            listOf("इमेज बनाना", "तस्वीर बनाना", "फोटो बनाना", "image generation", "image gen", "इमेज जनरेशन"), store = STORE_FLAGS),
        Item("vault", "बनी तस्वीरें प्राइवेट वॉल्ट में", "computer", FeatureFlags.FACE_VAULT, false,
            listOf("प्राइवेट वॉल्ट", "private vault", "वॉल्ट", "vault"), store = STORE_FLAGS),
        Item("youtube", "बोलकर YouTube चैनल जाँच", "social", FeatureFlags.YT_CHANNEL, false,
            listOf("youtube चैनल", "यूट्यूब चैनल", "चैनल जाँच", "चैनल जांच", "youtube channel"), store = STORE_FLAGS),
        Item("incognito", "इनकॉग्निटो मोड (कुछ याद न रखे)", "voice", FeatureFlags.INCOGNITO, false,
            listOf("इनकॉग्निटो", "incognito", "गुप्त मोड", "प्राइवेट मोड", "कुछ याद मत रखो"), store = STORE_FLAGS),
        Item("autoreply", "ऑटो रिप्लाई (मैसेज का अपने-आप जवाब)", "autoreply", "autoreply_on", false,
            listOf("ऑटो रिप्लाई", "auto reply", "autoreply", "ऑटो जवाब", "अपने आप जवाब"), store = STORE_CUSTOM),
        Item("autonomous", "ऑटोनोमस मोड", SettingsSearch.ACT_AUTO, "auto_on", false,
            listOf("ऑटोनोमस", "ऑटोनॉमस", "autonomous", "खुद तय करे"), store = STORE_CUSTOM),
        // 15 नवंबर 2026 (मालिक: "जो भी बना रहे हो, DK को पता हो - चालू-बंद कर सके"): नई सुविधाएँ
        Item("face", "चेहरा पहचान (DK लाइव: देखो कौन है)", "face", FeatureFlags.FACE_ON, false,
            listOf("चेहरा पहचान", "चेहरा पहचानना", "फेस पहचान", "फेस रिकग्निशन", "face recognition", "face id", "देखो कौन है"), store = STORE_FLAGS),
        Item("email", "ईमेल पढ़ना / भेजना", "computer", FeatureFlags.EMAIL_ON, false,
            listOf("ईमेल", "इमेल", "email", "gmail", "जीमेल", "मेल पढ़ना"), store = STORE_FLAGS),
        Item("wa_report", "WhatsApp ग्रुप रिपोर्ट", "autoreply", FeatureFlags.WA_GROUP_REPORT, false,
            listOf("ग्रुप रिपोर्ट", "group report", "ग्रुप में रिपोर्ट", "रिपोर्ट टेम्पलेट"), store = STORE_FLAGS),
        Item("char3d", "3D कैरेक्टर", "ui", FeatureFlags.CHARACTER_3D, false,
            listOf("3d कैरेक्टर", "3d character", "3डी कैरेक्टर", "कैरेक्टर", "character", "3d"), store = STORE_FLAGS),
        // 15 नवंबर 2026 (मालिक: "अलग 5-टैब वाली नीचे की पट्टी") - pref उल्टा: HIDE_HOME_TABS true = छुपी
        Item("home_tabs", "नीचे की 5-टैब पट्टी", "ui", FeatureFlags.HIDE_HOME_TABS, false,
            listOf("टैब पट्टी", "टैब वाली पट्टी", "नीचे की पट्टी", "टैब बार", "tab bar", "bottom bar", "bottom nav", "5 टैब", "पांच टैब", "पाँच टैब"),
            inverted = true, store = STORE_FLAGS),
        Item("friday", "FRIDAY मोड", "voice", AssistantPersona.PREF, false,
            listOf("फ्राइडे", "फ्राईडे", "फ्रायडे", "friday"), store = STORE_CUSTOM),
        Item("venom", "VENOM मोड", "voice", AssistantPersona.PREF, false,
            listOf("वेनम", "वेनोम", "venom"), store = STORE_CUSTOM),
        Item("god_mode", "टच मोड: गॉड मोड (सबसे सख़्त)", "guard", GuardModes.PREF, false,
            listOf("गॉड मोड", "god mode", "गॉड", "सबसे सख्त"), store = STORE_CUSTOM),
        Item("stealth", "टच मोड: स्टेल्थ मोड (चुपचाप)", "guard", GuardModes.PREF, false,
            listOf("स्टेल्थ", "stealth", "चुपचाप मोड", "साइलेंट गार्ड"), store = STORE_CUSTOM),
    )

    /** DK लाइव इन सुविधाओं को चालू होने पर सीधे चला भी सकता है (catalog में बताया जाता है) */
    const val RUN_HELP = "Features you can also RUN for the user (the feature must be ON first - if it is OFF, offer to turn it ON with dk_settings): " +
        "SOS = the phone runs it when the user clearly says 'SOS भेजो / activate SOS' (10-second countdown, 'SOS रोको' cancels); " +
        "driving mode = switch [driving]; event triggers = [events] + the event switches; " +
        "Termux = tool termux_run; free image = tool free_image; YouTube channel = tool youtube_channel; " +
        "touch mode = 'टच मोड चालू करो' (its style: [god_mode] = loudest, 10 photos, SMS always; [stealth] = silent, black screen, secret photos + SMS); " +
        "phone lock = 'फ़ोन लॉक करो'; teacher mode = tool teacher_mode; " +
        "face = tool face_who ('देखो कौन है' - Boss / saved person / unknown; faces are registered in Settings → चेहरा पहचान); " +
        "email = tools email_read / email_send / email_delete (send and delete only after reading it back and a clear yes); " +
        "WhatsApp group report = tool group_report (list / fill / send); 3D character = tool show_character; " +
        "FRIDAY / VENOM = switches [friday] / [venom] (one at a time, JARVIS goes off) or 'फ्राइडे मोड ऑन करो'. " +
        "Always call the user by his saved name (e.g. 'जी बॉस', 'हेलो बॉस')."

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
        appendLine(RUN_HELP)
        append("API keys, voice choice and model choice are changed by the user himself on the Settings screen - open that part for him.")
    }
}
