package com.dk.assistant.auto

import org.json.JSONObject

/**
 * ऑटोनोमस मोड की सुरक्षा (शुद्ध Kotlin -> JVM test):
 *  - पासवर्ड / OTP / PIN / बैंक / भुगतान वाली स्क्रीन पर DK कुछ नहीं करेगा
 *  - भेजना / पोस्ट / डिलीट जैसे वापस न होने वाले कदम पहचानना (पुष्टि वाला switch चालू हो तो पहले पूछना)
 *  - अटकना / बार-बार fail = रुककर बताना (अंतहीन कोशिश नहीं)
 */
object AutoSafety {
    private val SCREEN_WORDS = Regex(
        "(password|passcode|पासवर्ड|\\botp\\b|one[ -]?time[ -]?password|ओटीपी|verification code|enter (your )?pin|upi pin|" +
            "\\bmpin\\b|एमपिन|यूपीआई पिन|पिन डालें|cvv|card number|कार्ड नंबर|net ?banking|नेट ?बैंकिंग|enter amount|pay now|" +
            "proceed to pay|भुगतान करें)",
        RegexOption.IGNORE_CASE
    )
    /** बैंक / भुगतान ऐप - इन पर ऑटोनोमस मोड कुछ नहीं करता */
    private val MONEY_PKGS = listOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "com.sbi.", "com.csam.icici", "com.snapwork.hdfc", "com.axis.mobile", "com.msf.kbank", "com.bankofbaroda",
        "com.dreamplug.androidapp", "com.mobikwik_new", "com.amazon.mshop.android.shopping.pay"
    )

    /** null = ठीक; वरना रुकने का साफ़ कारण (हिंदी) */
    fun blockReason(screenText: String, pkg: String, hasPasswordField: Boolean): String? = when {
        hasPasswordField -> "स्क्रीन पर पासवर्ड वाली जगह है — सुरक्षा के लिए मैं यहाँ कुछ नहीं करूँगी, काम रोक दिया।"
        MONEY_PKGS.any { pkg.startsWith(it) } -> "यह बैंक / भुगतान वाला ऐप है — सुरक्षा के लिए मैं यहाँ कुछ नहीं करूँगी, काम रोक दिया।"
        SCREEN_WORDS.containsMatchIn(screenText) -> "स्क्रीन पर पासवर्ड / OTP / PIN / भुगतान जैसी जानकारी दिख रही है — सुरक्षा के लिए काम रोक दिया।"
        else -> null
    }

    private val IRREVERSIBLE = Regex(
        "(\\bsend\\b|भेज|\\bpost\\b|पोस्ट|publish|प्रकाशित|upload|अपलोड|share|शेयर|delete|डिलीट|हटाएँ|हटाओ|remove|" +
            "\\bnext\\b.*publish|reply|tweet|comment|कमेंट|\\bgo live\\b|confirm|पक्का)",
        RegexOption.IGNORE_CASE
    )
    private val SEND_APPS = listOf("com.whatsapp", "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
        "com.google.android.youtube", "org.telegram.messenger")

    /** एलिमेंट नंबर -> उसका लेबल (स्क्रीन वाली पंक्ति "[12] Button "Send" (Send)") */
    fun labelOf(screenText: String, id: Int): String =
        screenText.lineSequence().firstOrNull { it.startsWith("[$id] ") }?.substringAfter("] ").orEmpty()

    /** (x,y) के सबसे पास वाले एलिमेंट का लेबल (80px के अंदर) */
    fun labelAt(screenText: String, x: Int, y: Int): String {
        var best = ""; var bestD = 80 * 80
        for (line in screenText.lineSequence()) {
            val m = Regex("@(\\d+),(\\d+)\\s*$").find(line) ?: continue
            val dx = m.groupValues[1].toInt() - x; val dy = m.groupValues[2].toInt() - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = line.substringAfter("] ") }
        }
        return best
    }

    /** यह कदम भेजना/पोस्ट/डिलीट जैसा (वापस न हो सकने वाला) है? */
    fun irreversible(a: JSONObject, screenText: String, pkg: String): Boolean {
        val type = a.optString("type")
        if (type == "steps") {
            val st = a.optJSONArray("steps") ?: return false
            return (0 until st.length()).any { i -> st.optJSONObject(i)?.let { irreversible(it, screenText, pkg) } == true }
        }
        if (type in setOf("whatsapp_send", "send_message", "post", "delete", "end_call")) return true
        if (type == "enter") return SEND_APPS.any { pkg.startsWith(it) }
        if (type !in setOf("click", "tap", "long_click", "click_text")) return false
        val label = buildString {
            append(a.optString("text")); append(' '); append(a.optString("label")); append(' ')
            if (a.has("id")) append(labelOf(screenText, a.optInt("id", -1)))
            if (type == "tap") append(labelAt(screenText, a.optInt("x"), a.optInt("y")))
        }
        return IRREVERSIBLE.containsMatchIn(label)
    }

    /** "इमरजेंसी स्टॉप" वाले शब्द (ऑटोनोमस मोड चालू हो तभी) */
    val STOP_WORDS = setOf("इमरजेंसी स्टॉप", "इमर्जेंसी स्टॉप", "emergency stop", "ऑटोनोमस रोको", "ऑटोनोमस बंद", "सब काम रोको", "सारा काम रोको")

    /** कदम की छोटी पहचान (लॉग / पूछने के लिए) */
    fun describe(a: JSONObject, screenText: String): String {
        val type = a.optString("type")
        val label = when {
            a.has("id") -> labelOf(screenText, a.optInt("id", -1))
            type == "tap" -> labelAt(screenText, a.optInt("x"), a.optInt("y"))
            else -> ""
        }.replace(Regex("\\{[^}]*\\}|@\\d+,\\d+"), "").trim()
        return when (type) {
            "click", "tap", "long_click" -> "\"${label.ifBlank { "बटन" }.take(60)}\" दबाना"
            "type" -> "लिखना: \"${a.optString("text").take(80)}\""
            "enter" -> "Enter (भेजना) दबाना"
            "open_app" -> "${a.optString("app", a.optString("name"))} खोलना"
            "steps" -> "कई कदम एक साथ"
            else -> type
        }
    }

    /** एक जैसा कदम पहचानने का निशान. scroll/wait/back हर बार अलग (लगातार scroll अटकना नहीं) */
    fun sig(a: JSONObject, step: Int): String =
        if (a.optString("type") in setOf("scroll", "wait", "back", "look", "swipe")) "${a.optString("type")}#$step" else a.toString()

    /** पुष्टि का जवाब: साफ़ "हाँ" ही हाँ; "नहीं / मत / रुको" या चुप = नहीं */
    fun yes(ans: String?): Boolean {
        val t = ans?.lowercase()?.trim(' ', '.', '!', '।', ',', '?').orEmpty()
        if (t.isBlank()) return false
        if (Regex("(नहीं|नही|मत|रुको|ruko|cancel|रद्द|\\bno\\b|\\bnahi\\b|\\bmat\\b|\\bना\\b|^ना$)").containsMatchIn(t)) return false
        return Regex("(हाँ|हां|^हा$|जी|yes|\\bok\\b|okay|ओके|ठीक है|कर दो|भेज दो|पोस्ट कर|डिलीट कर|हटा दो|बिल्कुल|haan|han\\b|kar do|bhej do)").containsMatchIn(t)
    }

    /** बार-बार fail / वही कदम बार-बार = अटक गया */
    class Stuck(private val maxFails: Int = 3, private val maxSame: Int = 3) {
        private var fails = 0
        private var lastSig = ""
        private var same = 0
        /** true = अब रुको */
        fun note(sig: String, failed: Boolean): Boolean {
            fails = if (failed) fails + 1 else 0
            same = if (sig == lastSig) same + 1 else 1
            lastSig = sig
            return fails >= maxFails || same >= maxSame
        }
    }
}

/** ऑटोनोमस मोड का अभी चलता एक काम */
class AutoRun(val plan: AutoPlan.Plan, val confirm: Boolean) {
    @Volatile var screen: String = ""
    @Volatile var pkg: String = ""
    val stuck = AutoSafety.Stuck()
}
