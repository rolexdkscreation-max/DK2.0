package com.dk.assistant

import org.json.JSONObject

/**
 * Seedhi-saadi commands bina AI ke, turant (0.1 sec):
 * "WhatsApp खोलो", "टॉर्च जलाओ", "वापस जाओ", "फ़ोन लॉक करो", "कॉल उठा लो", "आवाज़ बढ़ाओ" ...
 * Lambi / kai-step wali baat (aur, फिर, भेजो, सर्च...) -> null (AI karega).
 */
object QuickCommands {
    data class Quick(val action: JSONObject, val ack: String)

    private fun a(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type); kv.forEach { o.put(it.first, it.second) }; return o
    }

    private val FILLER = listOf("प्लीज़", "please", "प्लीज", "ज़रा", "जरा", "जल्दी", "अभी", "तुरंत", "भाई", "यार", "जी", "ना")

    private val MULTI = listOf(" और ", " फिर ", " then ", " and ", " उसके बाद", "भेज", "मैसेज", "message", "सर्च", "search",
        "ढूंढ", "ढूँढ", " कार्ट", " cart", "पोस्ट", "डिलीट", "delete", "टाइप", "लिख", "बता", "क्या", "कैसे", "क्यों", "कौन", "किसका")

    fun parse(raw: String, wakeWords: List<String>): Quick? {
        var t = " " + raw.lowercase().trim().trim('.', '।', '!', '?', ',') + " "
        wakeWords.forEach { w -> if (w.length >= 2) t = t.replace(" $w ", " ").replace(" $w,", " ") }
        FILLER.forEach { t = t.replace(" $it ", " ") }
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun has(vararg w: String) = w.any { t.contains(it) }
        val words = t.split(' ').size
        val onW = has("जला", "चालू", "ऑन", " on", "on ", "खोल", "स्टार्ट", "start", "जगा")
        val offW = has("बंद", "ऑफ", " off", "off ", "बुझा", "हटा")

        // ---- "apna chehra dikhao" -> dots se chehra banta hai
        if (has("चेहरा", "चेहरे", "फेस", "फ़ेस", "face", "शक्ल", "सूरत", "chehra") &&
            has("दिखा", "दिखाओ", "show", "dikha", "देखना", "देखूं", "देखूँ", "बताओ कैसे") && !has("मेरा चेहरा", "मेरी शक्ल", "फेसबुक", "facebook"))
            return Quick(a("show_face"), "ये रहा मेरा चेहरा")
        // ---- screen dekhna (AI ko screenshot) chalu / band
        if (has("स्क्रीन", "screen") && has("देखना", "देखने", "देखो", "शेयर", "share", "दिखाना", "दिखाने") && (onW || offW) && words <= 8)
            return if (offW) Quick(a("screen_view", "on" to false, ScreenGuard.BY_USER to true), "ठीक है, अब मैं आपकी स्क्रीन नहीं देखूँगा")
                else Quick(a("screen_view", "on" to true, ScreenGuard.BY_USER to true), "ठीक है, अब मैं स्क्रीन देखकर काम करूँगा")

        // ---- calls (sabse pehle)
        if (has("कॉल", "call", "फोन", "फ़ोन") || has("उठा", "रिसीव", "receive", "answer")) {
            if (has("उठा", "रिसीव", "receive", "answer", "पिक", "pick", "उठाओ")) {
                val spk = has("स्पीकर", "speaker", "लाउड")
                return Quick(a("answer_call", "speaker" to spk), if (spk) "कॉल उठा कर स्पीकर पर डाल रहा हूँ" else "कॉल उठा रहा हूँ")
            }
            if (has("कट", "काट", "reject", "end", "रख दो", "रखो", "डिक्लाइन", "decline", "मना")) return Quick(a("end_call"), "कॉल काट दिया")
        }
        if (has("स्पीकर", "speaker") && words <= 5) {
            return if (offW) Quick(a("speaker", "on" to false), "स्पीकर बंद") else Quick(a("speaker", "on" to true), "स्पीकर चालू")
        }
        if (has("किसका कॉल", "किसका फोन", "किसका फ़ोन", "कौन कॉल", "who is calling")) return Quick(a("who_calls"), "")
        if (has("कॉल", "call")) return null          // "राहुल को कॉल करो" jaisi baat AI karega

        // ---- maps: rasta, navigation, doori, paas ki jagah (turant, bina AI)
        Maps.parse(t)?.let { return it }

        // ---- "YouTube पर <X> चलाओ" -> seedha pehla video (bina AI, bina search page) ~1 sec
        if (has("यूट्यूब", "यू ट्यूब", "youtube") && has("चला", "play", "प्ले", "लगा", "बजा", "सुना", "दिखा") &&
            !has(" और ", " फिर ", " then ", "भेज", "शेयर", "share", "डाउनलोड", "download", "सब्सक्राइब", "subscribe", "लाइक", "like", "कमेंट")) {
            val q = t.replace(Regex("(यूट्यूब|यू ट्यूब|youtube)\\s*(पर|पे|में|मे|me|par|pe|on)?"), " ")
                // v3.67: "यूट्यूब खोलो पवन सिंह का गाना चलाओ" - खोज में "खोलो" न जाए
                .replace(Regex("(?<![\\p{L}\\p{M}])(खोलो|खोल दो|खोल के|खोलकर|खोल|kholo|open|ओपन)(?![\\p{L}\\p{M}])"), " ")
                .replace(Regex("(चलाओ|चला दो|चलाइए|चलाना|चलाएं|चलाओं|चला|प्ले करो|प्ले कर दो|प्ले|play करो|play|लगाओ|लगा दो|लगा|बजाओ|बजा दो|बजा|सुनाओ|सुना दो|दिखाओ|दिखा दो|दिखा)"), " ")
                .replace(Regex("\\s+"), " ").trim().trim(',', '.')
            if (q.length >= 2) return Quick(a("youtube_play", "query" to q), "वीडियो चला दिया")
        }

        // ---- volume number ke saath: "आवाज़ 50 करो", "वॉल्यूम 70 परसेंट"
        if (has("आवाज़", "आवाज", "वॉल्यूम", "volume", "साउंड", "रिंगटोन", "ringtone", "घंटी")) {
            val ring = has("रिंगटोन", "ringtone", "घंटी", "रिंग")
            Regex("([0-9०-९]{1,3})").find(t)?.let { m ->
                val lvl = m.groupValues[1].toInt().coerceIn(0, 100)
                return Quick(a("volume", "direction" to "set", "level" to lvl, "stream" to (if (ring) "ring" else "")), "")
            }
            if (ring) {
                val dir = when {
                    has("बढ़ा", "बढा", "तेज़", "तेज", "ज़्यादा", "ज्यादा", "up") -> "up"
                    has("बंद", "म्यूट", "mute", "चुप") -> "mute"
                    else -> "down"
                }
                return Quick(a("volume", "direction" to dir, "stream" to "ring"), if (dir == "up") "रिंगटोन तेज़ कर दी" else "रिंगटोन धीमी कर दी")
            }
        }
        if (has("ब्लॉक", "block")) return null
        val padded = " $t "
        if (MULTI.any { if (it.startsWith(" ")) padded.contains(it) else t.contains(it) }) return null
        if (words > 6) return null

        // ---- torch
        if (has("टॉर्च", "torch", "फ्लैश", "flash", "लाइट", "light", "बत्ती")) {
            if (offW) return Quick(a("torch", "on" to false), "टॉर्च बंद")
            if (onW || words <= 2) return Quick(a("torch", "on" to true), "टॉर्च चालू")
        }
        // ---- lock / unlock
        if (has("अनलॉक", "unlock") || t == "फोन खोलो" || t == "फ़ोन खोलो" || has("स्क्रीन चालू", "स्क्रीन ऑन", "screen on"))
            return Quick(a("unlock"), "")
        if (has("लॉक", "lock") && !has("अनलॉक", "unlock", "सेटिंग")) return Quick(a("lock"), "")
        if (has("स्क्रीन बंद", "स्क्रीन ऑफ", "screen off")) return Quick(a("lock"), "")

        // ---- navigation
        if (has("होम स्क्रीन", "होम पर", "होम जा", "home") || t == "होम") return Quick(a("home"), "")
        if (has("वापस", "बैक", "back", "पीछे जा", "पीछे चल")) return Quick(a("back"), "")
        if (has("रीसेंट", "recent")) return Quick(a("recents"), "")
        if (has("नोटिफिकेशन", "notification")) return Quick(a("notifications"), "")
        if (has("क्विक सेटिंग", "quick setting")) return Quick(a("quick_settings"), "")
        if (has("स्क्रॉल", "scroll") || t == "नीचे करो" || t == "ऊपर करो" || t == "नीचे जाओ" || t == "ऊपर जाओ") {
            val dir = when { has("ऊपर", "up") -> "up"; has("बाएँ", "बाएं", "left") -> "left"; has("दाएँ", "दाएं", "right") -> "right"; else -> "down" }
            return Quick(a("scroll", "direction" to dir), "")
        }
        if (has("स्क्रीनशॉट", "screenshot")) return Quick(a("screenshot_save"), "स्क्रीनशॉट ले लिया")
        if (has("सारे ऐप", "सब ऐप", "सभी ऐप", "clear all", "close all", "सारे app", "सब app")) return Quick(a("close_all_apps"), "सारे ऐप बंद कर दिए")
        if ((has("ऐप", "app", "एप") && offW) || t == "बंद करो इसे" || t == "इसे बंद करो") return Quick(a("close_app"), "ऐप बंद कर दिया")

        // ---- volume / media
        if (has("आवाज़", "आवाज", "वॉल्यूम", "volume", "साउंड", "sound")) {
            return when {
                has("बढ़ा", "बढा", "तेज़", "तेज", "ज़्यादा", "ज्यादा", "up", "ऊँची") -> Quick(a("volume", "direction" to "up"), "")
                has("कम", "घटा", "धीमी", "down", "नीची") -> Quick(a("volume", "direction" to "down"), "")
                has("फुल", "full", "max", "पूरी") -> Quick(a("volume", "direction" to "max"), "")
                has("म्यूट", "mute") || offW -> Quick(a("volume", "direction" to "mute"), "")
                else -> null
            }
        }
        if (has("साइलेंट", "silent", "वाइब्रेट", "vibrate")) return Quick(a("volume", "direction" to "silent"), "फ़ोन साइलेंट पर")
        if (has("अगला गाना", "अगला वीडियो", "next song", "next")) return Quick(a("media", "key" to "next"), "")
        if (has("पिछला गाना", "पिछला वीडियो", "previous")) return Quick(a("media", "key" to "previous"), "")
        if (has("गाना रोको", "वीडियो रोको", "pause", "पॉज़", "पॉज")) return Quick(a("media", "key" to "pause"), "")
        if (t == "गाना चलाओ" || t == "वीडियो चलाओ" || t == "resume" || has("रिज़्यूम")) return Quick(a("media", "key" to "play"), "")

        // ---- settings page
        if (has("सेटिंग", "setting")) {
            val page = t.replace(Regex("(की |का |के )?(सेटिंग्स|सेटिंग्ज़|सेटिंग|settings|setting)"), " ")
                .replace(Regex("(खोलो|खोल दो|खोलिए|ओपन करो|ओपन|open|दिखाओ|में जाओ|पर जाओ|जाओ|करो)"), " ").trim()
            return Quick(a("open_settings", "page" to page.ifBlank { "main" }), "")
        }
        if (has("वाईफाई", "वाई-फाई", "wifi", "wi-fi")) return Quick(a("panel", "name" to "wifi"), "वाईफाई पैनल खोल दिया, यहाँ से चालू-बंद करें")
        if (has("ब्लूटूथ", "bluetooth")) return Quick(a("open_settings", "page" to "bluetooth"), "")
        if (has("हॉटस्पॉट", "hotspot")) return Quick(a("open_settings", "page" to "hotspot"), "")
        if (has("इंटरनेट", "मोबाइल डेटा", "data")) return Quick(a("panel", "name" to "internet"), "")

        // ---- app kholna:  "<app> खोलो" / "open <app>"
        val m = Regex("^(?:open\\s+|ओपन\\s+)?(.+?)(?:\\s+(?:ऐप|app|एप))?\\s*(खोलो|खोल दो|खोलिए|खोल|ओपन करो|ओपन कर दो|open करो|open|ओपन|चालू करो|स्टार्ट करो|लगाओ)$").find(t)
            ?: Regex("^(?:open|ओपन)\\s+(.+)$").find(t)
        if (m != null) {
            val app = m.groupValues[1].trim()
            if (app.isNotBlank() && app.split(' ').size <= 3 && listOf(" पर ", " में ", " को ", " से ").none { " $app ".contains(it) })
                return Quick(a("open_app", "name" to app), "")
        }
        return null
    }
}
