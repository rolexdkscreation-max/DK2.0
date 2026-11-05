package com.dk.assistant

import org.json.JSONObject

/**
 * नवंबर 2026: आवाज़ से फ़ोन लॉक / अनलॉक (Settings → फ़ोन लॉक / अनलॉक)।
 *
 * मालिक जो नाम और वाक्य Settings में डाले, वही पहचाने - जैसे "हे DK फोन लॉक करो", "हे मायरा फोन अनलॉक करो",
 * "हे जारवी फोन अनलॉक करो", "हे Google अनलॉक करो"। यह हिस्सा बिल्कुल सादा (Android नहीं) - test हो सके।
 * फ़ोन पर असली लॉक / PIN डालना [PhoneUnlocker] करता है, PIN ताले में [PhoneLockStore] रखता है।
 */
object PhoneLockVoice {
    /** फ़ोन नहीं खुला - पीछे सच्चा कारण (बोलकर बताना, AI को "हो गया" नहीं) */
    const val UNLOCK_FAIL = "UNLOCK_FAIL:"
    /** v4.8: फ़ेस / फ़िंगरप्रिंट स्क्रीन पर PIN / पैटर्न वाली जगह खोलने के बटन */
    val USE_SECRET_WORDS = listOf("Use PIN", "Use pattern", "Use password", "PIN इस्तेमाल करें", "पिन का उपयोग करें",
        "पैटर्न का उपयोग करें", "पासवर्ड का उपयोग करें", "PIN का उपयोग करें")

    /** v4.8 मालिक: खुलते ही यही बोले */
    const val UNLOCK_OK_SAY = "हाँ, लॉक स्क्रीन खुल गया है।"
    /** v4.8: न खुले तो साफ़ - "लॉक स्क्रीन नहीं खुला" पहले, फिर कारण */
    fun failSay(reason: String): String {
        val r = reason.trim()
        return if (r.startsWith("लॉक स्क्रीन नहीं खुला")) r else "लॉक स्क्रीन नहीं खुला है। $r".trim()
    }

    enum class Act(val type: String) { LOCK("lock"), UNLOCK("unlock") }

    enum class Kind(val id: String, val hi: String) {
        PIN("pin", "PIN"), PATTERN("pattern", "पैटर्न"), PASSWORD("password", "पासवर्ड");
        companion object { fun of(s: String?): Kind? = values().firstOrNull { it.id == s } }
    }

    const val DEFAULT_NAMES = "DK, डीके"
    /**
     * नवंबर 2026 (मालिक: "ऑफलाइन में डीके फोन लॉक कर दो नहीं सुनता"): फ़ोन की on-device (बिना नेट) हिंदी पहचान "डीके" को
     * अक्सर "डीजे" / "डी जे" / "DJ" / "टीके" लिखती है - नाम न मिला = पूरी बात अनसुनी. ये भी DK (सिर्फ़ लॉक / अनलॉक / किधर हो वाले वाक्यों में).
     */
    const val MISHEARD_NAMES = "डीजे, डी जे, दीजे, डिजे, dj, d j, डी के, दी के, डिके, टीके, टी के, deke, decay"
    const val DEFAULT_LOCK = "फोन लॉक करो, फोन लॉक कर दो, फोन लॉक कीजिए, फोन लॉक, मोबाइल लॉक करो, लॉक करो, लॉक कर दो, lock phone, phone lock karo, phone lock kar do, lock the phone, " +
        // नवंबर 2026 (मालिक): "डीके फोन ऑफ कर दो" = स्क्रीन बंद / लॉक (DK फ़ोन की बिजली बंद नहीं करता)
        "फोन ऑफ करो, फोन ऑफ कर दो, फोन ऑफ कीजिए, मोबाइल ऑफ करो, मोबाइल ऑफ कर दो, स्क्रीन ऑफ करो, स्क्रीन ऑफ कर दो, स्क्रीन बंद करो, स्क्रीन बंद कर दो, " +
        "फोन बंद करो, फोन बंद कर दो, phone off karo, phone off kar do, screen off karo, screen off, phone off"
    const val DEFAULT_UNLOCK = "फोन अनलॉक करो, फोन अनलॉक कर दो, फोन अनलॉक कीजिए, फोन अनलॉक, मोबाइल अनलॉक करो, अनलॉक करो, अनलॉक कर दो, अनलॉक, फोन खोलो, unlock phone, phone unlock karo, phone unlock kar do, unlock the phone, unlock, " +
        // नवंबर 2026 (मालिक): "डीके फोन चालू करो" = स्क्रीन जगाओ + अनलॉक
        "फोन चालू करो, फोन चालू कर दो, फोन ऑन करो, फोन ऑन कर दो, मोबाइल चालू करो, मोबाइल ऑन करो, स्क्रीन ऑन करो, स्क्रीन ऑन कर दो, स्क्रीन चालू करो, " +
        "phone on karo, phone on kar do, screen on karo, screen on, phone on"

    /** इनमें से कोई शब्द = मना (\"फोन लॉक मत करो\") - कुछ नहीं करना */
    private val NEG = setOf("मत", "नहीं", "नही", "mat", "nahi", "nahin", "dont", "don", "not", "never").map { norm(it) }.toSet()
    /** नाम से पहले / बीच के आम शब्द - गिनती में नहीं */
    private val FILL = setOf("हे", "है", "हाय", "हेलो", "हैलो", "ओके", "ओ", "सुनो", "अरे", "जरा", "प्लीज", "please", "plz",
        "hey", "hi", "hello", "ok", "okay", "o", "suno", "are", "arey", "zara", "jara", "भाई", "bhai", "यार", "yaar", "जी", "ji",
        "मेरा", "मेरे", "my", "अभी", "abhi", "now", "तुरंत", "जल्दी", "jaldi", "से", "se", "ना", "na", "the").map { norm(it) }.toSet()
    private val DEVICE = setOf("फोन", "मोबाइल", "स्क्रीन", "phone", "mobile", "screen", "fon").map { norm(it) }.toSet()
    /** नाम + वाक्य के अलावा इससे ज़्यादा शब्द = लंबी बात (किसी और बात में \"लॉक\" आया) - नहीं */
    const val MAX_EXTRA_WORDS = 1

    data class Cfg(
        val lockOn: Boolean = false,
        val unlockOn: Boolean = false,
        val names: List<String> = emptyList(),
        val lock: List<String> = emptyList(),
        val unlock: List<String> = emptyList(),
    ) { val on get() = lockOn || unlockOn }

    /** Settings से ताज़ा ([PhoneLockStore.reload]) - सुनने वाला loop और LocalRouter दोनों यही पढ़ते हैं */
    @Volatile var cfg: Cfg = Cfg()

    /** हिंदी लिखावट के फ़र्क (फ़/फ, ँ/ं, ॉ/ो...) और चिह्न मिटाओ */
    fun norm(s: String): String = SettingsSearch.norm(s).replace(Regex("\\s+"), " ").trim()

    /** "a, b\nc | d" → साफ़ सूची */
    fun list(raw: String?): List<String> =
        (raw ?: "").split(',', '\n', '|', '،').map { norm(it) }.filter { it.isNotEmpty() }.distinct()

    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }

    /** t में p पूरा शब्दों के साथ है? (\"लॉक करो\" ≠ \"अनलॉक करो\") → कहाँ से कहाँ तक (शब्द गिनती में) */
    private fun find(tw: List<String>, p: String): IntRange? {
        val pw = words(p); if (pw.isEmpty() || pw.size > tw.size) return null
        for (i in 0..tw.size - pw.size) if ((pw.indices).all { tw[i + it] == pw[it] }) return i until i + pw.size
        return null
    }

    /**
     * सुना हुआ वाक्य → लॉक / अनलॉक / null।
     * needName = वाक्य में बुलाने वाला नाम ज़रूरी (हमेशा सुनने वाला loop - वरना पास में कोई \"लॉक करो\" बोले तो फ़ोन लॉक)।
     * DK पहले से बुलाया जा चुका हो (LocalRouter) तो needName = false।
     */
    fun match(heard: String, needName: Boolean, c: Cfg = cfg): Act? {
        if (!c.on) return null
        val tw = words(norm(heard)); if (tw.isEmpty()) return null
        if (tw.any { it in NEG }) return null
        val used = BooleanArray(tw.size)
        var named = false
        for (n in c.names.sortedByDescending { words(it).size }) {
            val nw = words(n); if (nw.isEmpty() || nw.size > tw.size) continue
            for (i in 0..tw.size - nw.size) {
                if (nw.indices.all { !used[i + it] && tw[i + it] == nw[it] }) {
                    nw.indices.forEach { used[i + it] = true }; named = true; break
                }
            }
        }
        if (needName && !named) return null
        // अनलॉक पहले (उसके वाक्य में \"लॉक\" भी हो सकता है), लंबा वाक्य पहले
        val tries = (if (c.unlockOn) c.unlock.map { it to Act.UNLOCK } else emptyList()) +
            (if (c.lockOn) c.lock.map { it to Act.LOCK } else emptyList())
        for ((p, act) in tries.sortedByDescending { words(it.first).size }) {
            val r = find(tw, p) ?: continue
            if (r.any { used[it] }) continue
            val extra = tw.indices.count { it !in r && !used[it] && tw[it] !in FILL }
            // "फोन" वाला वाक्य: 1 फ़ालतू शब्द चलेगा; बिना "फोन" ("लॉक करो"): बिल्कुल नहीं -
            // वरना "व्हाट्सएप लॉक करो" पर पूरा फ़ोन लॉक हो जाता
            val limit = if (words(p).any { it in DEVICE }) MAX_EXTRA_WORDS else 0
            if (extra <= limit) return act
        }
        return null
    }

    /**
     * DK लाइव (आवाज़ से आवाज़ वाली बातचीत) के बीच: छोटा, पक्का वाक्य ("फोन लॉक करो", "डीके फोन अनलॉक करो") → तुरंत,
     * Gemini के जवाब / tool का इंतज़ार किए बिना। Settings में लॉक / अनलॉक बंद हो तब भी आम वाक्य चलते हैं
     * (पहले भी Live में "फोन लॉक करो" QuickCommands से लॉक करता था) - अनलॉक में PIN सिर्फ़ तब डलता है जब Settings में सेव हो।
     * names = DK के बुलाने वाले नाम (wake words) - वाक्य में हों तो गिनती में नहीं।
     * needName = "सिर्फ़ DK बोलने पर सुनो" चालू हो तो नाम ज़रूरी।
     */
    fun instant(heard: String, names: List<String> = emptyList(), needName: Boolean = false, c: Cfg = cfg): Act? {
        val all = (c.names + list(DEFAULT_NAMES) + list(MISHEARD_NAMES) + names.map { norm(it) }).filter { it.isNotEmpty() }.distinct()
        val use = Cfg(
            lockOn = true, unlockOn = true, names = all,
            lock = if (c.lockOn && c.lock.isNotEmpty()) (c.lock + list(DEFAULT_LOCK)).distinct() else list(DEFAULT_LOCK),
            unlock = if (c.unlockOn && c.unlock.isNotEmpty()) (c.unlock + list(DEFAULT_UNLOCK)).distinct() else list(DEFAULT_UNLOCK),
        )
        return match(heard, needName, use)
    }

    /**
     * v3.151: फ़ोन पर PIN/पैटर्न है पर DK के पास नहीं (या आवाज़ से अनलॉक बंद) - DK क्या बोले।
     * पहले यह सफल गिना जाता था, तो Live "हो गया" बोल देता और फ़ोन बंद रहता।
     */
    fun noPinReason(unlockOn: Boolean, hasSecret: Boolean): String = UNLOCK_FAIL + " " + when {
        !hasSecret -> "फ़ोन पर PIN / पैटर्न लगा है और DK के पास वह सेव नहीं है, इसलिए मैं खोल नहीं सकता। " +
            "DK की Settings → फ़ोन लॉक / अनलॉक में \"आवाज़ से फ़ोन अनलॉक करो\" चालू करके अपना PIN या पैटर्न एक बार सेव कीजिए। अभी फ़िंगरप्रिंट या PIN से खुद खोलिए।"
        !unlockOn -> "आवाज़ से अनलॉक Settings में बंद है। DK की Settings → फ़ोन लॉक / अनलॉक में \"आवाज़ से फ़ोन अनलॉक करो\" चालू करके सेव दबाइए।"
        else -> "स्क्रीन चालू कर दी है, पर PIN डालना अभी रुका है। फ़ोन खुद खोलिए।"
    }

    /** "lock" / "unlock" / "wake" = फ़ोन का ताला वाला काम (दोबारा बोला = दोबारा करो, छोटी दोहराव-खिड़की) */
    fun isLockType(type: String?) = type == "lock" || type == "unlock" || type == "wake"

    /** फ़ोन पर चलने वाला काम (QuickCommands जैसा ही - वही \"lock\" / \"unlock\") */
    fun quick(act: Act) = QuickCommands.Quick(JSONObject().put("type", act.type), "")

    // ------------------------------------------------------------ PIN / पैटर्न / पासवर्ड

    fun validPin(p: String) = p.length in 4..16 && p.all { it in '0'..'9' }
    /** पैटर्न = 3x3 के बिंदु 1-9 (ऊपर बाएँ 1 … नीचे दाएँ 9), हर बिंदु एक बार, कम से कम 4 */
    fun validPattern(p: String) = p.length in 4..9 && p.all { it in '1'..'9' } && p.toSet().size == p.length
    fun validPassword(p: String) = p.length in 4..64 && p.none { it == '\n' }
    fun valid(k: Kind, s: String) = when (k) { Kind.PIN -> validPin(s); Kind.PATTERN -> validPattern(s); Kind.PASSWORD -> validPassword(s) }

    /**
     * Android जैसा: दो बिंदुओं के बीच वाला बिंदु (1→3 में 2, 1→9 में 5) अपने-आप जुड़ता है अगर पहले न लगा हो।
     * पैटर्न बनाने वाला pad यही इस्तेमाल करता है - ताकि सेव हुआ क्रम लॉक स्क्रीन के क्रम जैसा ही हो।
     */
    fun between(a: Char, b: Char): Char? {
        val ai = a - '1'; val bi = b - '1'
        val r1 = ai / 3; val c1 = ai % 3; val r2 = bi / 3; val c2 = bi % 3
        if ((r1 + r2) % 2 != 0 || (c1 + c2) % 2 != 0) return null
        val mid = ((r1 + r2) / 2) * 3 + (c1 + c2) / 2
        return if (mid == ai || mid == bi) null else '1' + mid
    }

    /** पैटर्न के बिंदु का बीच (स्क्रीन पर pattern वाले हिस्से के अंदर) */
    fun cell(d: Char, left: Int, top: Int, right: Int, bottom: Int): Pair<Float, Float> {
        val i = d - '1'
        val w = (right - left) / 3f; val h = (bottom - top) / 3f
        return Pair(left + w * (i % 3 + 0.5f), top + h * (i / 3 + 0.5f))
    }

    // ------------------------------------------------------------ लॉक स्क्रीन के बटन पहचानना

    private const val DEV = "०१२३४५६७८९"
    private fun dig(ch: Char): Char? = when (ch) { in '0'..'9' -> ch; in DEV -> '0' + DEV.indexOf(ch); else -> null }

    /** लॉक स्क्रीन का अंक वाला बटन? (id \"…:id/key5\" / लिखावट \"5\" / \"5 JKL\" / \"५\") → अंक */
    fun digitKey(id: String?, text: CharSequence?, desc: CharSequence?): Char? {
        id?.substringAfterLast('/')?.lowercase()?.let { s ->
            Regex("^(?:key|digit|num|number|pin_?key|keypad_?key|btn)_?([0-9])$").find(s)?.let { return it.groupValues[1][0] }
        }
        for (raw in listOf(text, desc)) {
            val s = raw?.toString()?.trim().orEmpty(); if (s.isEmpty()) continue
            val first = dig(s[0]) ?: continue
            val rest = s.drop(1).trim(' ', ',', '\n')
            if (rest.isEmpty() || rest.all { it.isLetter() || it == '+' || it == ' ' }) return first
        }
        return null
    }

    private val ENTER_WORDS = setOf("enter", "ok", "done", "go", "next", "confirm", "ठीक है", "ठीक", "दर्ज करें", "पुष्टि करें", "✓", "→")
    fun enterKey(id: String?, text: CharSequence?, desc: CharSequence?): Boolean {
        val i = id?.substringAfterLast('/')?.lowercase().orEmpty()
        if (i == "key_enter" || i == "enter" || i == "key_ok" || i == "ok" || i == "btn_ok" || i.endsWith("enter_key")) return true
        return listOf(text, desc).any { it?.toString()?.trim()?.lowercase() in ENTER_WORDS }
    }

    fun patternView(cls: CharSequence?, id: String?): Boolean {
        val c = cls?.toString().orEmpty(); val i = id?.lowercase().orEmpty()
        return c.contains("LockPatternView") || c.contains("PatternView") || i.contains("lockpatternview") ||
            i.contains("lock_pattern") || i.contains("pattern_view")
    }
}
