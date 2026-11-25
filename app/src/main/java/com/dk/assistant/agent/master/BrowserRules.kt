package com.dk.assistant.agent.master

import org.json.JSONObject

/**
 * 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 2 - "फिर ब्राउज़र").
 * ब्राउज़र एजेंट का pure हिस्सा (JVM test). असली ब्राउज़र पहले से बना [com.dk.assistant.ComputerBrowser] ही है -
 * यहाँ सिर्फ़ उसके ऊपर मास्टर के नियम: कौन-सा बटन दबाने से पहले पूछना है, पेज पर कौन-सा ख़तरा है, AI को क्या बताना है.
 * [FeatureFlags.MASTER_AGENT] बंद = इसका कोई हिस्सा नहीं चलता (ब्राउज़र बिल्कुल पहले जैसा).
 */
object BrowserRules {
    /** दबाने से पहले पूछना - [hi] यूज़र को दिखने वाला कारण */
    enum class Click(val hi: String) {
        PAY("भुगतान"),
        BUY("खरीदारी / ऑर्डर"),
        BOOK("बुकिंग"),
        SUBMIT("फ़ॉर्म जमा करना"),
        DELETE("मिटाना / रद्द करना"),
        SEND("भेजना / पोस्ट करना"),
    }

    private fun norm(s: String) = " " + s.lowercase().replace(Regex("[\\s\\u00a0]+"), " ").trim() + " "
    private fun has(l: String, w: List<String>) = w.any { l.contains(it) }

    private val PAY = listOf(" pay ", "pay now", "pay ₹", "pay rs", "make payment", "proceed to pay", "proceed to payment", "complete payment",
        "भुगतान", "पेमेंट", "पे करें", "पैसे भेज", " upi ", "add money", "transfer", "send money")
    private val BUY = listOf("buy now", "place order", "place your order", "confirm order", "proceed to checkout", "checkout", "check out",
        "add to cart", "add to bag", "खरीदें", "अभी खरीद", "ऑर्डर करें", "ऑर्डर दें", "कार्ट में", "subscribe", "start free trial", "upgrade")
    private val BOOK = listOf("book now", "book ticket", "confirm booking", "reserve", "बुक करें", "बुक नाउ", "टिकट बुक", "continue to book")
    private val SUBMIT = listOf(" submit ", "submit application", "final submit", "apply now", "register now", "sign up", "create account",
        "जमा करें", "सबमिट", "आवेदन करें", "रजिस्टर करें")
    private val DELETE = listOf(" delete ", "remove account", "deactivate", "cancel order", "cancel booking", "unsubscribe", "मिटाएँ", "डिलीट", "रद्द करें")
    private val SEND = listOf(" send ", " post ", " publish ", "share now", "tweet", "भेजें", "पोस्ट करें", "शेयर करें")

    /** बटन / लिंक का नाम (और पेज का URL) -> पूछना है? null = आराम से दबाओ */
    fun riskyClick(label: String, url: String = ""): Click? {
        val l = norm(label)
        if (l.isBlank()) return null
        return when {
            has(l, PAY) -> Click.PAY
            has(l, BOOK) -> Click.BOOK
            has(l, DELETE) -> Click.DELETE     // "unsubscribe" / "cancel order" पहले - वरना BUY समझ लेता
            has(l, BUY) -> Click.BUY
            has(l, SUBMIT) -> Click.SUBMIT
            has(l, SEND) -> Click.SEND
            // भुगतान वाले पेज पर "continue / proceed / confirm" भी पैसे वाला कदम
            isPaymentUrl(url) && has(l, listOf("continue", "proceed", "confirm", " ok ", "आगे", "पक्का")) -> Click.PAY
            else -> null
        }
    }

    fun isPaymentUrl(url: String): Boolean {
        val u = url.lowercase()
        return listOf("/checkout", "/payment", "/pay/", "/pay?", "paytm.", "razorpay", "billdesk", "payu", "ccavenue", "/upi", "phonepe.com/pay")
            .any { u.contains(it) }
    }

    /** पुष्टि वाले कार्ड की लाइन */
    fun confirmText(k: Click, label: String, url: String): String =
        "DK ब्राउज़र \"${label.trim().take(60)}\" दबाना चाहता है (${k.hi}).\n\nपेज: ${url.take(120)}\n\nदबाऊँ?"

    /** मना हुआ = AI को साफ़ बताओ ताकि दोबारा न दबाए */
    fun deniedNote(k: Click, label: String) =
        "STOPPED: the owner did NOT allow \"${label.trim().take(60)}\" (${k.name.lowercase()}). Do NOT click it again or find another way; tell the owner in one short Hindi line."

    /** भुगतान कभी DK नहीं करता - पूछने की भी ज़रूरत नहीं */
    fun payNote(label: String) =
        "STOPPED: \"${label.trim().take(60)}\" is a PAYMENT step. DK never pays. Stop here and tell the owner to open this page and pay himself (give him the page link)."

    /** पेज पर खतरे वाले खाने: पासवर्ड / OTP / कार्ड / CAPTCHA / भुगतान */
    const val PAGE_GUARD_JS = """(function(){
var q=function(s){try{return document.querySelectorAll(s).length>0}catch(x){return false}};
var txt=((document.body&&document.body.innerText)||'').slice(0,20000).toLowerCase();
var lab=[].slice.call(document.querySelectorAll('input')).map(function(e){return ((e.name||'')+' '+(e.id||'')+' '+(e.getAttribute('autocomplete')||'')+' '+(e.getAttribute('placeholder')||'')).toLowerCase()}).join(' ');
return JSON.stringify({
 pw:q('input[type=password]'),
 otp:/otp|one-time-code|verification code/.test(lab)||q('input[autocomplete=one-time-code]'),
 card:/cc-number|cardnumber|card_number|cvv|cvc/.test(lab),
 captcha:q('iframe[src*=recaptcha],iframe[src*=hcaptcha],.g-recaptcha,.h-captcha,[class*=captcha],[id*=captcha]')||/i'm not a robot|i am not a robot|captcha/.test(txt),
 pay:/pay now|make payment|proceed to pay|upi id|enter upi|debit card|credit card|net banking|भुगतान करें/.test(txt)
});})()"""

    data class Guard(val pw: Boolean = false, val otp: Boolean = false, val card: Boolean = false, val captcha: Boolean = false, val pay: Boolean = false) {
        val any get() = pw || otp || card || captcha || pay
    }

    fun parseGuard(raw: String): Guard = try {
        val o = JSONObject(raw)
        Guard(o.optBoolean("pw"), o.optBoolean("otp"), o.optBoolean("card"), o.optBoolean("captcha"), o.optBoolean("pay"))
    } catch (_: Exception) { Guard() }

    /** ब्राउज़र के नतीजे के आख़िर में AI के लिए लाइन (कुछ नहीं मिला = खाली) */
    fun pageNote(g: Guard, url: String): String {
        if (!g.any) return ""
        val what = listOf(g.pw to "password", g.otp to "OTP", g.card to "card / CVV", g.captcha to "CAPTCHA", (g.pay || isPaymentUrl(url)) to "payment")
            .filter { it.first }.joinToString(", ") { it.second }
        return "\n\nDK MASTER: this page asks for $what. DK must NOT fill or solve it. Stop and ask the owner to do this part himself on his phone " +
            "(give him the page link: ${url.take(160)}); continue only after he says it is done."
    }

    /** हिंदी में छोटा कारण - लॉग / स्क्रीन */
    fun pageHi(g: Guard): String = listOf(g.pw to "पासवर्ड", g.otp to "OTP", g.card to "कार्ड", g.captcha to "CAPTCHA", g.pay to "भुगतान")
        .filter { it.first }.joinToString(", ") { it.second }

    /** DK कंप्यूटर के system prompt में (मास्टर चालू हो तो) */
    const val AGENT_NOTE = "- DK MASTER (browser): first read the page (browse without actions) and act only on the numbered elements you saw; " +
        "after every click check that the page really changed (TITLE / URL / text) - if not, try ONE other way, then tell the owner; " +
        "never click pay / buy / book / submit / delete / send without the owner's yes (DK asks him automatically - if he says no, stop); " +
        "never make a payment - hand the payment page link to the owner; never type passwords, OTPs, card numbers or personal details you were not given; " +
        "on CAPTCHA / login / OTP screens stop and ask the owner. In done, say only what the page confirmed - never claim something happened that you did not see."

    /** ब्राउज़र के एक action का लॉग वाला छोटा रूप ("type" का लिखा हुआ text नहीं - निजी हो सकता है) */
    fun logLine(a: JSONObject): String = when {
        a.has("click") -> "दबाया: ${a.opt("click")}"
        a.has("type") -> "लिखा: खाना ${a.optJSONArray("type")?.opt(0)}"
        a.has("enter") -> "Enter: ${a.opt("enter")}"
        a.has("select") -> "चुना: ${a.optJSONArray("select")?.opt(0)}"
        a.has("scroll") -> "स्क्रॉल: ${a.optString("scroll")}"
        a.has("back") -> "पीछे"
        a.has("wait") -> "रुका ${a.optLong("wait")}ms"
        a.has("js") -> "पेज स्क्रिप्ट"
        else -> a.toString().take(40)
    }
}
