package com.dk.assistant.agent.master

/**
 * 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 3 - "शॉपिंग एजेंट").
 * शॉपिंग एजेंट का pure हिस्सा (JVM test). असली काम पहले से बने [com.dk.assistant.ShopOpen] (shop_search) और
 * फ़ोन वाला एजेंट ([com.dk.assistant.AgentRunner]) ही करते हैं - यहाँ सिर्फ़ उनके ऊपर मास्टर के नियम:
 *  - बात से बजट / साइज़ / "सबसे सस्ता" समझना ([want]) और खोज में जोड़ना ([query])
 *  - स्क्रीन पर सच में दिखे दाम पढ़ना ([offers]) - जो स्क्रीन पर नहीं, वह दाम कभी नहीं बोलना
 *  - कौन-सा बटन: कार्ट (पूछकर), खरीदें / ऑर्डर (पूछकर), भुगतान (कभी नहीं - आपको सौंपना) ([tap])
 * [com.dk.assistant.plus.FeatureFlags.MASTER_AGENT] बंद = इसका कोई हिस्सा नहीं चलता (शॉपिंग बिल्कुल पहले जैसी).
 */
object ShopRules {
    data class Want(val maxPrice: Int? = null, val minPrice: Int? = null, val size: String? = null, val cheapest: Boolean = false) {
        val any get() = maxPrice != null || minPrice != null || size != null || cheapest
    }

    private fun num(s: String): Int? = s.replace(",", "").trim().let { t ->
        when {
            t.endsWith("k") -> t.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toInt() }
            else -> t.toDoubleOrNull()?.toInt()
        }
    }?.takeIf { it in 1..10_000_000 }

    private const val N = "(\\d[\\d,]*(?:\\.\\d+)?k?)"
    private const val RS = "(?:₹|rs\\.?|inr|रुपये|रुपए|रुपया|रूपये|rupees?|rupaye)?"
    private val MAX = listOf(
        Regex("(?:under|below|less than|within|upto|up to|max|maximum)\\s*$RS\\s*$N"),
        Regex("$RS\\s*$N\\s*$RS\\s*(?:के अंदर|के अन्दर|के नीचे|से कम|से नीचे|तक|तक का|तक की|तक के|ke andar|se kam|tak|से ज़्यादा नहीं|से ज्यादा नहीं)"),
        Regex("(?:बजट|budget)\\s*(?:है|hai|:)?\\s*$RS\\s*$N"),
    )
    private val MIN = listOf(
        Regex("(?:above|more than|over|minimum|min)\\s*$RS\\s*$N"),
        Regex("$RS\\s*$N\\s*$RS\\s*(?:से ऊपर|से ज़्यादा|से ज्यादा|se upar|se zyada)"),
    )
    private val RANGE = Regex("$RS\\s*$N\\s*(?:से|se|to|-|–)\\s*$RS\\s*$N\\s*$RS\\s*(?:के बीच|तक|ke beech|tak|between)?")
    private val SIZE_LETTER = Regex("(?:साइज़|साइज|size|सइज़)\\s*(xxs|xs|s|m|l|xl|xxl|xxxl|2xl|3xl|\\d{1,2})\\b")
    private val SIZE_NUM = Regex("(\\d{1,2})\\s*(?:नंबर|number|no\\.?)\\s*(?:का|की|के)?\\s*(?:जूता|जूते|shoe|shoes|चप्पल)")
    private val CHEAP = listOf("सबसे सस्ता", "सबसे सस्ती", "सबसे सस्ते", "सस्ता वाला", "सस्ती वाली", "cheapest", "lowest price", "low to high", "sasta", "सस्ता दिखाओ")

    /** बात -> बजट / साइज़ / सस्ता. कुछ नहीं मिला = खाली [Want] */
    fun want(text: String): Want {
        val t = " " + text.lowercase().replace('\u00a0', ' ') + " "
        var max: Int? = null
        var min: Int? = null
        RANGE.find(t)?.let { m ->
            val a = num(m.groupValues[1]); val b = num(m.groupValues[2])
            if (a != null && b != null && b > a && (m.value.contains("बीच") || m.value.contains("beech") || m.value.contains("between") || m.value.contains("तक") || m.value.contains("tak") || m.value.contains(" to "))) {
                min = a; max = b
            }
        }
        // 20 से कम = दाम नहीं ("2 दिन के अंदर")
        if (max == null) max = MAX.firstNotNullOfOrNull { r -> r.find(t)?.let { num(it.groupValues[1]) }?.takeIf { it >= 20 } }
        if (min == null) min = MIN.firstNotNullOfOrNull { r -> r.find(t)?.let { num(it.groupValues[1]) }?.takeIf { it >= 20 } }
        val size = SIZE_LETTER.find(t)?.groupValues?.get(1)?.uppercase() ?: SIZE_NUM.find(t)?.groupValues?.get(1)
        return Want(max, min, size, CHEAP.any { t.contains(it) })
    }

    /** खोज का शब्द + साइज़ / बजट ("black shirt for men size M under 500") */
    fun query(item: String, w: Want): String {
        val q = StringBuilder(item.trim())
        val low = item.lowercase()
        w.size?.let { s -> if (!low.contains("size")) q.append(" size ").append(s) }
        if (w.maxPrice != null && !low.contains("under")) q.append(" under ").append(w.maxPrice)
        return q.toString().trim()
    }

    data class Offer(val title: String, val price: Int)

    private val PRICE = Regex("(?:₹|\\brs\\.?\\s?|\\binr\\s?)\\s?(\\d[\\d,]{1,8})(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)
    private val NOT_TITLE = Regex("(?i)(\\d+%|% off|\\boff\\b|\\bratings?\\b|\\breviews?\\b|★|\\bstars?\\b|free delivery|delivery by|sponsored|\\bad\\b|^\\d[\\d.,\\s]*$)")

    /** स्क्रीन की पंक्ति "[12] Text "Black Shirt" @200,300" -> "Black Shirt" */
    private fun clean(line: String): String = line
        .replace(Regex("^\\s*\\[\\d+]\\s*"), "")
        .replace(Regex("^(Text|Button|View|Image|ImageView|TextView|Link|Item|Card|Element|EditText|CheckBox)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("@\\d+,\\d+\\s*$"), "")
        .replace("\"", " ").replace(Regex("\\s+"), " ").trim()

    /**
     * स्क्रीन पर सच में दिखे दाम (नाम + सबसे पहला दाम = आमतौर पर बिक्री वाला; कटा हुआ पुराना दाम बाद में आता है).
     * नाम = दाम वाली पंक्ति में ही लिखा हो तो वही, वरना ऊपर की सबसे पास वाली नाम जैसी पंक्ति.
     */
    fun offers(screen: String, max: Int = 12): List<Offer> {
        val lines = screen.lineSequence().map { clean(it) }.filter { it.isNotBlank() }.toList()
        val out = ArrayList<Offer>()
        val seen = HashSet<String>()
        var lastTitle = ""
        var used = ""
        for (l in lines) {
            val m = PRICE.find(l)
            if (m == null) {
                if (l.length in 8..140 && !NOT_TITLE.containsMatchIn(l) && l.count { it.isLetter() } >= 5) lastTitle = l
                continue
            }
            val p = m.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            if (p < 10) continue
            val own = l.replace(PRICE, " ").replace(Regex("\\s+"), " ").trim()
            val title = if (own.length >= 8 && !NOT_TITLE.containsMatchIn(own) && own.count { it.isLetter() } >= 5) own else lastTitle
            if (title.isBlank() || title == used) continue   // एक ही चीज़ के दूसरे दाम (MRP / कटा हुआ) नहीं
            val key = title.lowercase().take(60)
            if (!seen.add(key)) continue
            out += Offer(title.take(80), p)
            used = title
            if (out.size >= max) break
        }
        return out
    }

    /** बजट के अंदर, सस्ते पहले */
    fun fit(list: List<Offer>, w: Want): List<Offer> = list
        .filter { o -> (w.maxPrice == null || o.price <= w.maxPrice) && (w.minPrice == null || o.price >= w.minPrice) }
        .sortedBy { it.price }

    fun rupee(p: Int): String = "₹" + String.format(java.util.Locale.US, "%,d", p)

    /** AI के लिए: स्क्रीन पर पढ़े दाम (नहीं मिले = खाली) */
    fun priceNote(list: List<Offer>, w: Want): String {
        if (list.isEmpty()) return ""
        val ok = fit(list, w)
        val show = (if (w.any) ok else list.sortedBy { it.price }).take(3)
        val b = StringBuilder("\nDK MASTER (shopping) - prices DK READ ON THE SCREEN right now (use ONLY these, never guess a price): ")
        b.append(list.take(6).joinToString(" | ") { "${it.title.take(50)} = ${rupee(it.price)}" })
        if (w.maxPrice != null || w.minPrice != null) {
            b.append(". Within his budget")
            w.minPrice?.let { b.append(" from ").append(rupee(it)) }
            w.maxPrice?.let { b.append(" up to ").append(rupee(it)) }
            b.append(": ").append(if (ok.isEmpty()) "none visible on this screen - say so honestly and offer to scroll or change the search" else "${ok.size} visible")
        }
        if (show.isNotEmpty()) b.append(". Cheapest visible: ").append(show.joinToString(", ") { "${it.title.take(40)} ${rupee(it.price)}" })
        b.append(". Tell him in one or two short Hindi lines. Never add to cart, buy or pay by yourself.")
        return b.toString()
    }

    /** लॉग / स्क्रीन के लिए छोटा हिंदी सार */
    fun priceHi(list: List<Offer>, w: Want): String {
        val ok = fit(list, w).ifEmpty { if (w.maxPrice == null && w.minPrice == null) list.sortedBy { it.price } else emptyList() }
        if (ok.isEmpty()) return if (list.isEmpty()) "स्क्रीन पर दाम नहीं पढ़ पाया" else "बजट के अंदर कुछ नहीं दिखा"
        return "सबसे सस्ते: " + ok.take(3).joinToString(" • ") { "${it.title.take(30)} ${rupee(it.price)}" }
    }

    /** शॉपिंग में बटन का तरीका */
    enum class Tap(val hi: String) { CART("कार्ट में डालना"), BUY("खरीदना / ऑर्डर"), PAY("भुगतान") }

    private fun has(l: String, w: List<String>) = w.any { l.contains(it) }
    private val PAY_W = listOf(" pay ", "pay now", "pay ₹", "pay rs", "make payment", "proceed to pay", "proceed to payment", "complete payment", "confirm payment",
        "continue to payment", "भुगतान", "पेमेंट", "पे करें", " upi ", "upi id", "upi pin", "debit card", "credit card", "net banking", "netbanking", "emi ",
        "cash on delivery", "pay on delivery", "add money", "wallet balance")
    private val BUY_W = listOf("buy now", "buy at", "place order", "place your order", "confirm order", "order now", "proceed to checkout", "checkout", "check out",
        "proceed to buy", "continue to checkout", "अभी खरीदें", "खरीदें", "ऑर्डर करें", "ऑर्डर दें", "ऑर्डर प्लेस")
    private val CART_W = listOf("add to cart", "add to bag", "add to basket", "add to trolley", "add item", "कार्ट में डालें", "कार्ट में जोड़ें", "बैग में डालें", "बैग में जोड़ें")

    /** बटन का नाम -> [Tap] (null = आम बटन, आराम से दबाओ) */
    fun tap(label: String): Tap? {
        val l = " " + label.lowercase().replace(Regex("[\\s\\u00a0\"()]+"), " ").trim() + " "
        if (l.isBlank()) return null
        return when {
            has(l, PAY_W) -> Tap.PAY
            has(l, BUY_W) -> Tap.BUY
            has(l, CART_W) -> Tap.CART
            else -> null
        }
    }

    /** मालिक ने खुद कार्ट में डालने को कहा ("कार्ट में डाल दो") - तब कार्ट के लिए दोबारा नहीं पूछना */
    fun cartAsked(goal: String): Boolean {
        val g = goal.lowercase()
        return listOf("कार्ट में डाल", "कार्ट में जोड़", "कार्ट में ऐड", "cart me daal", "cart mein daal", "cart me add", "add to cart", "add it to cart",
            "बैग में डाल", "bag me daal", "कार्ट में रख").any { g.contains(it) }
    }

    /** भुगतान वाला बटन: DK कभी नहीं दबाता - यही लाइन मालिक को */
    const val PAY_HANDOVER = "यहाँ से भुगतान का कदम है — यह मैं कभी नहीं करती। ऐप इसी पेज पर खुला है, भुगतान आप खुद कीजिए। बाद में देखना हो तो बोलिए — पेज दिखाओ।"

    fun askLine(k: Tap, label: String): String =
        "${k.hi} वाला कदम: \"${label.trim().take(60)}\"। क्या मैं दबाऊँ? हाँ या ना बोलिए।"

    /** मास्टर चालू हो तो फ़ोन एजेंट / Live को साथ में जाने वाला नियम */
    const val AGENT_NOTE = "DK MASTER (shopping): add to cart only the exact item he asked for (name, colour, size, price limit) and only after his yes; " +
        "tell prices only as read on the screen - never guess or invent a price, rating or offer; " +
        "Buy now / Place order / Checkout = ask him first; never tap Pay / UPI / card / net banking / cash on delivery - stop and hand the payment to him."
}
