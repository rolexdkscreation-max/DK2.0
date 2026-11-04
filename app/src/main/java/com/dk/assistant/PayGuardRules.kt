package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "पेमेंट करते समय पेमेंट ऐप बोलता है पहले DK की accessibility बंद करो"):
 * फ़ोन के सारे पेमेंट ऐप अपने-आप पहचानो; वो खुलते ही DK का फ़ोन कंट्रोल (accessibility) बंद,
 * पेमेंट करके बाहर आते ही फिर चालू. यह हिस्सा सिर्फ़ नियम (pure - JVM test), Android वाला [PayGuard].
 */
object PayGuardRules {
    data class App(val pkg: String, val label: String, val upi: Boolean)

    /** जाने-माने पेमेंट / बैंक ऐप (नाम में "pay" / "bank" न हो तब भी) */
    val KNOWN = setOf(
        "com.google.android.apps.nbu.paisa.user",   // Google Pay
        "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",   // PhonePe, Paytm, BHIM
        "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp",   // MobiKwik, Freecharge, CRED
        "money.super.payments", "com.naviapp", "com.fampay.in", "com.slice",
        "com.sbi.lotusintouch", "com.sbi.upi", "com.csam.icici.bank.imobile", "com.snapwork.hdfc",
        "com.enstage.wibmo.hdfc", "com.axis.mobile", "com.msf.kbank.mobile", "com.bankofbaroda.mconnect",
        "com.canarabank.mobility", "com.infrasofttech.indianBank", "com.idbibank.abhay_card",
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile", "com.squareup.cash", "com.venmo",
    )

    /** UPI इनके अंदर सिर्फ़ एक हिस्सा है (मुख्य काम चैट / खरीदारी) - सूची में दिखें, पर अपने-आप बंद नहीं; आप चालू कर सकते हैं */
    val NOT_MAINLY_PAY = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "in.amazon.mShop.android.shopping", "com.flipkart.android",
        "in.swiggy.android", "com.application.zomato", "com.truecaller", "com.instagram.android",
        "com.facebook.katana", "com.facebook.orca", "com.jio.myjio", "com.myairtelapp",
        "com.google.android.gm", "com.android.chrome", "com.google.android.googlequicksearchbox",
    )

    /** पेमेंट के बीच पल भर आने वाली चीज़ें (ऐप चुनने वाला, कीबोर्ड, इजाज़त) - इन्हें "बाहर आ गए" न मानो */
    val TRANSIENT = setOf(
        "android", "com.android.intentresolver", "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.google.android.gms", "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard", "com.touchtype.swiftkey",
    )

    private val WORDS = listOf("bank", "upi", "wallet", "yono", "imobile", "netbanking", "pay", "बैंक")

    fun looksPay(pkg: String, label: String): Boolean {
        val l = label.lowercase()
        val p = pkg.lowercase()
        return pkg in KNOWN || WORDS.any { l.contains(it) } || p.contains("bank") || p.contains(".upi")
    }

    /** फ़ोन के ऐप में से पेमेंट वाले: UPI से पैसे भेज सकने वाले (upi://pay) + जाने-माने + नाम से */
    fun detect(apps: List<App>, own: String): List<App> =
        apps.filter { it.pkg != own && (it.upi || looksPay(it.pkg, it.label)) }
            .groupBy { it.pkg }.map { (_, v) -> v.first().copy(upi = v.any { it.upi }) }
            .sortedBy { it.label.lowercase() }

    fun defaultOn(pkg: String): Boolean = pkg !in NOT_MAINLY_PAY

    fun shouldPause(enabled: Boolean, pkg: String?, guarded: Set<String>): Boolean =
        enabled && pkg != null && pkg in guarded

    /** पेमेंट ऐप से बाहर आने के बाद इतनी देर रुककर चालू (पल भर के लिए बाहर-अंदर पर बार-बार बंद-चालू नहीं) */
    const val RESUME_AFTER_MS = 2_500L

    /** सामने वाला ऐप पेमेंट का नहीं (और पल भर वाला नहीं) और कुछ देर से वही है = फिर चालू. DK खुद खुला = तुरंत */
    fun shouldResume(fgPkg: String?, fgSince: Long, now: Long, guarded: Set<String>, own: String): Boolean =
        fgPkg != null && fgPkg !in guarded && fgPkg !in TRANSIENT &&
            (fgPkg == own || now - fgSince >= RESUME_AFTER_MS)

    /** होम स्क्रीन पर किसी आइकन पर टैप: उसका नाम किसी पेमेंट ऐप का? (ऐप खुलने से पहले ही बंद - उसकी जाँच से पहले) */
    fun tappedPkg(text: String?, labels: Map<String, String>): String? {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        return labels.entries.firstOrNull { it.value.trim().lowercase() == t }?.key
    }
}
