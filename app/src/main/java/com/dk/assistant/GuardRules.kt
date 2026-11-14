package com.dk.assistant

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * नवंबर 2026 (मालिक): "टच मोड" (पहले नाम "गार्ड मोड") - कोई दूसरा फ़ोन छुए / उठाए तो फ़ोन लॉक, ज़ोर का सायरन, DK खुद चेतावनी बोले.
 * यह हिस्सा बिल्कुल सादा (Android नहीं) - JVM test ([GuardRulesTest]). Android वाला हिस्सा [Guard].
 */
object GuardRules {
    const val TYPE = "guard"
    /** मालिक का रखा नाम - हर जगह (Settings, DK की बोली, SMS) यही दिखे */
    const val NAME_HI = "टच मोड"

    // ------------------------------------------------------------------ बोलकर चालू / बंद

    sealed class Cmd {
        /** @param minutes 0 = जब तक बंद न करें */
        data class On(val minutes: Int) : Cmd()
        /** बंद (गुप्त शब्द साथ में बोला हो तो वही बात [heard] में - जाँच [codeOk]) */
        data class Off(val heard: String) : Cmd()
    }

    private val NAME = listOf("स्क्रीन गार्ड", "स्क्रीनगार्ड", "screen guard", "screenguard", "गार्ड", "गार्ड्स", "गाड", "गारड", "गार्ड़", "guard", "gard", "garde", "सिक्योरिटी", "सेक्यूरिटी", "security",
        "सुरक्षा", "चौकीदार", "रखवाली", "एंटी थेफ्ट", "एंटीथेफ्ट", "anti theft", "antitheft", "चोर पकड़", "चोर वाला")
    /**
     * 14 नवंबर 2026 (मालिक: "स्क्रीन गार्ड का नाम टच मोड; जब बोला जाए 'DK टच मोड ऑन करो' तभी ऑन, नहीं तो नहीं"):
     * चालू सिर्फ़ इन नामों + चालू वाले शब्द से. पुराने नाम (स्क्रीन गार्ड / गार्ड / सुरक्षा…) से सिर्फ़ बंद.
     */
    private val TOUCH = listOf("टच मोड", "टचमोड", "टच मॉड", "टच मोड़", "टच मोद", "touch mode", "touchmode", "touch mod", "tach mode",
        "टच गार्ड", "touch guard")
    /** यह बात मालिक का साफ़ "टच मोड ऑन करो" है? (DK लाइव का screen_guard tool भी इसी से जाँचता है) */
    fun touchOn(raw: String): Boolean = parse(raw) is Cmd.On
    private val MODE = listOf("मोड", "मॉड", "mode", "moad", "सिस्टम", "system")
    private val ON = listOf("ऑन", "आन", " on", "चालू", "शुरू", "स्टार्ट", "start", "लगा", "एक्टिव", "activate", "active", "chalu", "enable", "इनेबल")
    private val OFF = listOf("ऑफ", " off", "बंद", "हटा", "band", "disable", "deactivate", "रोक", "stop", "स्टॉप")
    /** सिर्फ़ गार्ड का सायरन बजते समय ("अलार्म बंद करो" घड़ी वाले अलार्म का काम है - यहाँ नहीं) */
    private val SIREN = listOf("सायरन", "साइरन", "सायरेन", "siren", "sairan", "सिरेन")
    private val NOT = listOf(" मत ", "नहीं", "नही", "don't", "do not", " mat ", " nahi ")
    /** सवाल ("गार्ड मोड कैसे चालू करें") = AI समझाए, फ़ोन पर कुछ नहीं */
    private val ASK = listOf("कैसे", "क्या है", " क्या ", " kya ", "क्या होता", "kaise", " how ", " what ", "बताओ", "समझाओ", "explain")

    fun norm(t: String) = " " + t.lowercase().replace("\u093C", "").replace('ं', 'ँ')
        .replace(Regex("[,.!?।\"'`]+"), " ").replace(Regex("\\s+"), " ").trim() + " "

    /** " on" जैसे (आगे खाली जगह) = पूरा शब्द; बाकी = शब्द का हिस्सा भी ("लगाओ" में "लगा") */
    private fun has(t: String, w: List<String>) = w.any { if (it.startsWith(" ")) t.contains(norm(it)) else t.contains(norm(it).trim()) }

    /** "DK गार्ड मोड चालू करो" / "1 घंटे के लिए गार्ड मोड ऑन करो" / "गार्ड मोड बंद करो" / "सायरन बंद करो" */
    fun parse(raw: String, alarming: Boolean = false): Cmd? {
        val t = norm(raw)
        if (t.length > 120) return null
        val touch = has(t, TOUCH)
        val named = touch || has(t, NAME)
        val siren = alarming && has(t, SIREN) && has(t, OFF)
        if (!named && !siren) return null
        if (named && !has(t, MODE) && !has(t, ON) && !has(t, OFF)) return null
        if (has(t, NOT) || has(t, ASK)) return null
        // "बंद" पहले देखो: "टच मोड चालू है तो बंद करो" = बंद
        if (siren || has(t, OFF)) return Cmd.Off(raw)
        // चालू = सिर्फ़ "टच मोड" नाम + साफ़ चालू वाला शब्द (ऑन / चालू / शुरू / लगाओ…). "गार्ड मोड करो" / "सुरक्षा चालू" से नहीं
        if (touch && has(t, ON)) return Cmd.On(minutes(t))
        return null
    }

    private val NUM = mapOf("एक" to 1.0, "डेढ" to 1.5, "ढाई" to 2.5, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0, "पाँच" to 5.0, "छह" to 6.0,
        "छः" to 6.0, "सात" to 7.0, "आठ" to 8.0, "नौ" to 9.0, "दस" to 10.0, "पंद्रह" to 15.0, "बीस" to 20.0, "तीस" to 30.0, "चालीस" to 40.0,
        "पैंतालीस" to 45.0, "आधा" to 0.5, "आधे" to 0.5, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "ten" to 10.0, "half" to 0.5, "ek" to 1.0, "do" to 2.0, "teen" to 3.0)
        .mapKeys { norm(it.key).trim() }

    // norm() "ं" को "ँ" बनाता है - इसलिए इकाइयाँ भी उसी से
    private val HOUR = listOf("घंटे", "घंटा", "घण्टे", "घंटों", "hours", "hour", "ghante", "ghanta").joinToString("|") { norm(it).trim() }
    private val MIN = listOf("मिनट", "मिनिट", "minutes", "minute", "min").joinToString("|") { norm(it).trim() }

    private fun num(s: String): Double? = s.toDoubleOrNull() ?: NUM[s]

    /** "2 घंटे के लिए" = 120, "30 मिनट" = 30, "आधे घंटे" = 30, "डेढ़ घंटा" = 90; न बोला = 0 (जब तक बंद न करें) */
    fun minutes(t0: String): Int {
        val t = norm(t0)
        Regex("([^\\s]+) ($HOUR)").find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: 1.0
            return (n * 60).toInt().coerceIn(1, 24 * 60)
        }
        Regex("([^\\s]+) ($MIN)").find(t)?.let { m ->
            num(m.groupValues[1])?.let { n -> return n.toInt().coerceIn(1, 24 * 60) }
        }
        if (Regex("(^| )($HOUR) ").containsMatchIn(t)) return 60
        return 0
    }

    // ------------------------------------------------------------------ गुप्त शब्द (बोलकर सायरन / गार्ड बंद)

    /** मालिक का रखा गुप्त शब्द - कम से कम इतने अक्षर */
    const val MIN_CODE = 3

    private fun squash(t: String) = norm(t).replace(" ", "")

    /** सुनी बात में गुप्त शब्द है? (खाली / बहुत छोटा शब्द = कभी नहीं) */
    fun codeOk(heard: String, code: String): Boolean {
        val c = squash(code)
        if (c.length < MIN_CODE) return false
        return squash(heard).contains(c)
    }

    // ------------------------------------------------------------------ हिलना / उठाना

    enum class Sense(val hi: String, val angleDeg: Double, val jolt: Double, val joltMs: Long) {
        LOW("कम (सिर्फ़ उठाने पर)", 25.0, 3.5, 250),
        MEDIUM("मध्यम (उठाना / खिसकाना)", 12.0, 1.8, 150),
        HIGH("ज़्यादा (हल्का छूना भी)", 5.0, 0.8, 80);

        companion object { fun of(id: String?) = values().firstOrNull { it.name == id } ?: MEDIUM }
    }

    /**
     * accelerometer के नमूने (m/s²) -> "किसी ने छुआ / उठाया".
     * पहले [SETTLE_MS] तक फ़ोन रखा माना (आधार = gravity की दिशा), फिर: दिशा [Sense.angleDeg] से ज़्यादा बदली, या
     * gravity से हटकर झटका [Sense.jolt] से ज़्यादा लगातार [Sense.joltMs] तक.
     */
    class Motion(private val sense: Sense) {
        private var startAt = -1L
        private var sx = 0.0; private var sy = 0.0; private var sz = 0.0; private var n = 0
        private var base: DoubleArray? = null
        private var joltSince = -1L
        private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
        var lastWhy = ""
            private set

        val ready get() = base != null

        /** @return true = हिला (अलार्म) */
        fun feed(x: Double, y: Double, z: Double, t: Long): Boolean {
            if (startAt < 0) startAt = t
            val b = base
            if (b == null) {
                if (t - startAt < SETTLE_MS) {
                    sx += x; sy += y; sz += z; n++
                    return false
                }
                if (n == 0) { sx = x; sy = y; sz = z; n = 1 }
                base = doubleArrayOf(sx / n, sy / n, sz / n)
                fx = sx / n; fy = sy / n; fz = sz / n
                return false
            }
            // धीमा फ़िल्टर = दिशा (gravity); सीधा फ़र्क = झटका
            fx += (x - fx) * 0.2; fy += (y - fy) * 0.2; fz += (z - fz) * 0.2
            val ang = angle(b[0], b[1], b[2], fx, fy, fz)
            if (ang > sense.angleDeg) { lastWhy = "फ़ोन की दिशा ${ang.toInt()}° बदली"; return true }
            val dx = x - fx; val dy = y - fy; val dz = z - fz
            val j = sqrt(dx * dx + dy * dy + dz * dz)
            if (j > sense.jolt) {
                if (joltSince < 0) joltSince = t
                if (t - joltSince >= sense.joltMs) { lastWhy = "झटका ${"%.1f".format(java.util.Locale.US, j)} m/s²"; return true }
            } else joltSince = -1L
            return false
        }
    }

    const val SETTLE_MS = 1_000L

    fun angle(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val la = sqrt(ax * ax + ay * ay + az * az); val lb = sqrt(bx * bx + by * by + bz * bz)
        if (la < 0.5 || lb < 0.5) return 0.0
        val c = ((ax * bx + ay * by + az * bz) / (la * lb)).coerceIn(-1.0, 1.0)
        return acos(c) * 180.0 / PI
    }

    // ------------------------------------------------------------------ लॉक स्क्रीन पर क्या खुला

    private val POWER_OFF = listOf("power off", "पावर बंद", "पावर ऑफ", "switch off", "turn off", "स्विच ऑफ़", "स्विच ऑफ", "बंद करें")
    private val POWER_MORE = listOf("restart", "रीस्टार्ट", "पुनः आरंभ", "reboot", "emergency", "आपातकाल", "lockdown")
    private val SHADE = listOf("airplane mode", "aeroplane mode", "flight mode", "हवाई जहाज़", "हवाई जहाज", "एयरप्लेन", "airplane",
        "mobile data", "मोबाइल डेटा", "internet", "इंटरनेट", "wi-fi", "wifi", "वाई-फ़ाई", "वाई-फाई", "bluetooth", "ब्लूटूथ", "location", "लोकेशन")
    private val BOUNCER = listOf("enter pin", "enter your pin", "pin दर्ज", "पिन दर्ज", "pin डालें", "पिन डालें", "enter password", "पासवर्ड दर्ज",
        "draw your pattern", "draw pattern", "पैटर्न बनाएँ", "पैटर्न बनाएं", "पैटर्न ड्रॉ", "wrong pin", "गलत पिन", "incorrect pin", "wrong pattern",
        "गलत पैटर्न", "wrong password", "try again in", "फिर से कोशिश करें")

    private fun count(texts: List<String>, w: List<String>): Int {
        val all = texts.joinToString(" ") { it.lowercase() }
        return w.count { all.contains(it) }
    }

    /** पावर मेनू (बंद / रीस्टार्ट) खुला */
    fun isPowerMenu(texts: List<String>) = count(texts, POWER_OFF) >= 1 && count(texts, POWER_MORE) >= 1
    /** ऊपर का पैनल (एयरप्लेन / Wi-Fi / डेटा) खुला */
    fun isQuickSettings(texts: List<String>) = count(texts, SHADE) >= 2
    /** PIN / पैटर्न डालने वाला खाना खुला = कोई खोलने की कोशिश */
    fun isBouncer(texts: List<String>) = count(texts, BOUNCER) >= 1

    // ------------------------------------------------------------------ सायरन / चेतावनी

    const val SAY = "चेतावनी! यह फ़ोन आपका नहीं है। इसे तुरंत वापस रखिए। आपकी फ़ोटो ले ली गई है।"
    const val ARM_SAY = "टच मोड चालू हो रहा है। फ़ोन नीचे रख दीजिए।"
    /** सायरन कितनी देर तक (फिर चुप, पर गार्ड और लॉक बने रहते हैं - फिर हिला तो फिर बजेगा) */
    const val ALARM_MAX_MS = 10 * 60_000L
    /** इतनी देर सायरन, फिर DK की चेतावनी, फिर थोड़ा चुप (ताकि मालिक का गुप्त शब्द सुनाई दे) */
    const val SIREN_MS = 8_000L
    const val LISTEN_GAP_MS = 2_500L
    const val ARM_DELAY_MS = 5_000L
    const val MAX_PHOTOS = 30

    /**
     * पुलिस जैसा "वाऊँ-वाऊँ" सायरन (PCM16 mono, [rate] Hz): 600 Hz से 1500 Hz तक ऊपर-नीचे, [cycleMs] का एक चक्कर.
     */
    fun sirenPcm(rate: Int, ms: Int, cycleMs: Int = 1_200): ShortArray {
        val n = rate * ms / 1000
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val tt = (i.toDouble() / rate * 1000.0 % cycleMs) / cycleMs        // 0..1
            val sweep = if (tt < 0.5) tt * 2 else (1 - tt) * 2                  // 0..1..0
            val f = 600.0 + 900.0 * sweep
            ph += 2 * PI * f / rate
            if (ph > 2 * PI) ph -= 2 * PI
            // थोड़ा "चौकोर" = ज़्यादा तीखी आवाज़
            val s = sin(ph); val v = (s + 0.3 * sin(3 * ph)) / 1.3
            out[i] = (v * 32_000).toInt().coerceIn(-32_767, 32_767).toShort()
        }
        return out
    }

    /**
     * भरोसेमंद नंबर को SMS. [accM] = लोकेशन कितनी सटीक (मीटर), [n] = कौन-सा SMS (0 = पहला, 1.. = बाद की अपडेट).
     * 5 दशमलव (~1 मीटर, GPS की अपनी सटीकता से बेहतर) - और अब GPS वाली सटीक, ताज़ा जगह ही भेजी जाती है ([pickBest]).
     */
    fun smsText(why: String, at: String, lat: Double?, lon: Double?, accM: Float? = null, n: Int = 0, gps: Boolean = true): String =
        (if (n == 0) "DK $NAME_HI: आपका फ़ोन किसी ने छुआ/उठाया ($why) - $at."
            else "DK $NAME_HI: लोकेशन अपडेट #$n - $at (फ़ोन अभी भी मालिक के पास नहीं).") +
            (if (lat != null && lon != null) " सटीक लोकेशन: https://maps.google.com/?q=${"%.5f".format(java.util.Locale.US, lat)},${"%.5f".format(java.util.Locale.US, lon)}" +
                (accM?.let { " (±${accLabel(it)}${if (gps) ", GPS" else ", नेटवर्क - GPS नहीं मिला"})" } ?: "")
            else " लोकेशन नहीं मिली (GPS / लोकेशन बंद या सिग्नल नहीं) - अगली अपडेट में फिर कोशिश.")

    fun accLabel(m: Float): String = if (m < 1000f) "${m.toInt().coerceAtLeast(1)} मीटर" else "${"%.1f".format(java.util.Locale.US, m / 1000f)} किमी"

    // ------------------------------------------------------------------ सटीक लोकेशन (GPS) + बार-बार भेजना

    /** एक लोकेशन (Android Location से अलग, ताकि JVM test चल सके). [ageMs] = कितनी पुरानी */
    data class Fix(val lat: Double, val lon: Double, val accM: Float, val gps: Boolean, val ageMs: Long = 0L)

    /** इतनी सटीक (मीटर) मिल गई = और इंतज़ार नहीं */
    const val LOC_GOOD_M = 20f
    /** सटीक GPS के लिए ज़्यादा से ज़्यादा इंतज़ार (खुले में GPS को ठंडी शुरुआत पर 20-40 s लगते हैं) */
    const val LOC_WAIT_MS = 45_000L
    /** इससे पुरानी लोकेशन (फ़ोन की याद में पड़ी) कभी नहीं - वह "उधर की" पुरानी जगह होती है */
    const val LOC_MAX_AGE_MS = 60_000L
    /** इससे कम सटीक (जैसे मोबाइल टावर वाली ~1-3 किमी) - तभी भेजें जब कुछ और न मिले, साफ़ लिखकर */
    const val LOC_ROUGH_M = 500f

    /**
     * कई providers (GPS / नेटवर्क / fused) से आई लोकेशनों में सबसे सटीक, ताज़ा वाली.
     * पुरानी (> [LOC_MAX_AGE_MS]) या बेकार (accuracy 0 / NaN) छोड़ो. बराबर हो तो GPS वाली.
     */
    fun pickBest(fixes: List<Fix>): Fix? = fixes
        .filter { it.ageMs in 0..LOC_MAX_AGE_MS && it.accM > 0f && !it.accM.isNaN() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && !(it.lat == 0.0 && it.lon == 0.0) }
        .minWithOrNull(compareBy<Fix> { it.accM }.thenBy { if (it.gps) 0 else 1 })

    /** अब और इंतज़ार की ज़रूरत नहीं? (GPS से [LOC_GOOD_M] के अंदर) */
    fun goodEnough(f: Fix?): Boolean = f != null && f.gps && f.accM <= LOC_GOOD_M

    /**
     * पहला SMS भेजने के बाद अगली लोकेशन कब: पहले आधे घंटे हर 2 मिनट (चोर अभी पास ही होगा), फिर 2 घंटे तक हर 5 मिनट,
     * फिर हर 15 मिनट - जब तक मालिक फ़ोन न खोले / चेहरा / गुप्त शब्द से टच मोड बंद न हो.
     * @param sent अब तक कितने SMS गए (पहला मिलाकर)
     */
    fun nextLocDelayMs(sent: Int): Long = when {
        sent < 15 -> 2 * 60_000L          // 15 x 2 मिनट = 30 मिनट
        sent < 39 -> 5 * 60_000L          // + 24 x 5 मिनट = 2.5 घंटे
        else -> 15 * 60_000L
    }

    /** एक बार में ज़्यादा से ज़्यादा इतने SMS (कोई गड़बड़ हो तो SMS का बिल न बढ़े) - ~24 घंटे */
    const val LOC_MAX_SMS = 120

    /** दो लोकेशन SMS के बीच कम से कम इतना समय (कई जगह से trigger हो तब भी एक साथ 2 SMS नहीं) */
    const val LOC_MIN_GAP_MS = 60_000L

    // ------------------------------------------------------------------ मालिक का चेहरा (सायरन अपने-आप बंद)

    /** चेतावनी स्क्रीन पर कैमरे से मालिक को पहचानें? सब सच हो तभी */
    fun faceCheck(faceOn: Boolean, enrolled: Boolean, ready: Boolean, camOk: Boolean): Boolean = faceOn && enrolled && ready && camOk

    /** चेहरा पहचान क्यों नहीं चलेगी - Settings / इतिहास में यही लिखा जाए. null = चलेगी */
    fun faceWhyNot(faceOn: Boolean, enrolled: Boolean, ready: Boolean, camOk: Boolean): String? = when {
        !faceOn -> "चेहरे से बंद करना Settings में बंद है"
        !enrolled -> "मालिक का चेहरा सेट नहीं - Settings → $NAME_HI → \"मालिक का चेहरा सेट करें\""
        !ready -> "चेहरा पहचान मॉडल तैयार नहीं - Settings → $NAME_HI में चेहरा फिर से सेट करें"
        !camOk -> "कैमरे की इजाज़त नहीं"
        else -> null
    }

    /** लगातार इतने फ़्रेम में मालिक मिले = सायरन बंद (एक फ़्रेम के धोखे से नहीं) */
    const val OWNER_HITS = 2

    /** मोबाइल नंबर ठीक है? (+91 / 0 / 10 अंक) */
    fun validNumber(n: String): Boolean = n.replace(Regex("[\\s-]"), "").matches(Regex("\\+?\\d{10,13}"))

    /** टाइमर: [until] बीत गया = गार्ड अपने-आप बंद (0 = कोई टाइमर नहीं) */
    fun expired(until: Long, now: Long) = until > 0L && now >= until

    /** चालू करते समय DK क्या बोले */
    fun armSay(minutes: Int, hasCode: Boolean): String =
        (if (minutes > 0) "${label(minutes)} के लिए $NAME_HI" else NAME_HI) + " 5 सेकंड में चालू। फ़ोन नीचे रख दीजिए।" +
            if (hasCode) "" else " बंद करने के लिए फ़ोन अनलॉक करें, या सेटिंग्स में गुप्त शब्द रखें।"

    fun label(minutes: Int): String = when {
        minutes % 60 == 0 -> "${minutes / 60} घंटे"
        minutes > 60 -> "${minutes / 60} घंटे ${minutes % 60} मिनट"
        else -> "$minutes मिनट"
    }
}
