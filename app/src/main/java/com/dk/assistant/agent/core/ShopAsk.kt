package com.dk.assistant.agent.core

/**
 * v3.156 मालिक: "मुझे शर्ट / जींस / जूते / घड़ी / लोअर देखना है" -> DK: "ठीक है, दिखाता हूँ" ->
 * फ़ोन में जो shopping ऐप हैं (Flipkart, Meesho, Amazon...) उनमें से पूछो "किस ऐप पर दिखाऊँ?" ->
 * जो ऐप बोले उसी में, जो चीज़ बोली वही खोजो.  (pure Kotlin - Android नहीं, test हो सके)
 */
object ShopAsk {
    data class Shop(val key: String, val label: String, val pkgs: List<String>, val words: List<String>)

    /** पसंद का क्रम = पूछते समय यही क्रम */
    val SHOPS = listOf(
        Shop("flipkart", "Flipkart", listOf("com.flipkart.android"), listOf("flipkart", "फ्लिपकार्ट", "फ्लिपकाट", "फ्लिप कार्ट", "flip kart")),
        Shop("meesho", "Meesho", listOf("com.meesho.supply"), listOf("meesho", "मीशो", "मेसो", "मिशो", "मीसो", "misho")),
        Shop("amazon", "Amazon", listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"), listOf("amazon", "अमेज़न", "अमेजन", "अमेज़ॉन", "एमेजॉन", "amezon")),
        Shop("myntra", "Myntra", listOf("com.myntra.android"), listOf("myntra", "मिंत्रा", "मंत्रा", "मिन्त्रा")),
        Shop("ajio", "Ajio", listOf("com.ril.ajio"), listOf("ajio", "अजियो", "एजियो", "अजीओ")),
    )

    /** बोली गई चीज़ -> खोजने का शब्द (shopping ऐप अंग्रेज़ी में सबसे अच्छा ढूँढते हैं) */
    private val ITEMS = listOf(
        listOf("टी-शर्ट", "टीशर्ट", "टी शर्ट", "t-shirt", "tshirt", "t shirt") to "t-shirt",
        listOf("शर्ट", "shirt", "सर्ट") to "shirt",
        listOf("जींस", "जीन्स", "jeans", "जिंस") to "jeans",
        listOf("लोअर", "लॉयर", "लोवर", "lower", "ट्रैक पैंट", "track pant") to "lower track pants",
        listOf("पैंट", "पेंट", "pant", "trouser", "ट्राउज़र") to "pant",
        listOf("जूते", "जूता", "शूज़", "शूज", "शू", "shoes", "shoe", "sneaker", "स्नीकर") to "shoes",
        listOf("चप्पल", "सैंडल", "slipper", "sandal") to "slippers",
        listOf("घड़ी", "घडी", "वॉच", "वाच", "watch", "स्मार्टवॉच", "smartwatch") to "watch",
        listOf("कुर्ता", "kurta") to "kurta",
        listOf("साड़ी", "साडी", "saree", "sari") to "saree",
        listOf("जैकेट", "jacket") to "jacket",
        listOf("हुडी", "hoodie") to "hoodie",
        listOf("बैग", "bag") to "bag",
        listOf("चश्मा", "सनग्लास", "sunglass") to "sunglasses",
        listOf("पर्स", "वॉलेट", "wallet") to "wallet",
        listOf("बेल्ट", "belt") to "belt",
        listOf("टोपी", "कैप", "cap") to "cap",
        listOf("मोबाइल", "स्मार्टफोन", "smartphone") to "smartphone",
        listOf("ईयरफोन", "ईयरबड", "हेडफोन", "earbuds", "earphone", "headphone") to "earbuds",
    )

    /** "देखना है / दिखाओ / खरीदना है / चाहिए" */
    private val WANT = listOf("देखना", "देखनी", "देखने", "दिखाओ", "दिखा दो", "दिखाइए", "दिखाना", "खरीदना", "खरीदनी", "लेना है", "लेनी है",
        "चाहिए", "dekhna", "dekhni", "dikhao", "dikha do", "kharidna", "chahiye", "show me", "want to buy", "i want")

    private val COLORS = mapOf("काला" to "black", "काली" to "black", "काले" to "black", "सफ़ेद" to "white", "सफेद" to "white",
        "लाल" to "red", "नीला" to "blue", "नीली" to "blue", "नीले" to "blue", "हरा" to "green", "हरी" to "green",
        "पीला" to "yellow", "पीली" to "yellow", "भूरा" to "brown", "ग्रे" to "grey", "गुलाबी" to "pink")
    private val FOR_WHOM = mapOf("लड़कों" to "men", "लड़के" to "men", "मर्द" to "men", "जेंट्स" to "men", "men" to "men",
        "लड़कियों" to "women", "लड़की" to "women", "लेडीज़" to "women", "लेडीज" to "women", "women" to "women", "बच्चों" to "kids", "kids" to "kids")

    fun shopIn(text: String): Shop? {
        val t = text.lowercase()
        return SHOPS.firstOrNull { s -> s.words.any { t.contains(it) } }
    }

    /** बात में चीज़ है तो खोजने का शब्द ("black shirt men"), नहीं तो null */
    fun item(text: String): String? {
        val t = " ${text.lowercase()} "
        val base = ITEMS.firstOrNull { (w, _) -> w.any { t.contains(it) } }?.second ?: return null
        val extra = buildList {
            COLORS.entries.firstOrNull { t.contains(" ${it.key} ") }?.let { add(it.value) }
            add(base)
            FOR_WHOM.entries.firstOrNull { t.contains(it.key) }?.let { add("for " + it.value) }
        }
        return extra.joinToString(" ")
    }

    /** "मुझे शर्ट देखना है" = shopping की इच्छा (ऐप का नाम हो या न हो) */
    fun isWish(text: String): Boolean {
        val t = text.lowercase()
        return item(text) != null && WANT.any { t.contains(it) }
    }

    /** फ़ोन में जो shopping ऐप हैं (क्रम = SHOPS) */
    fun installed(has: (String) -> Boolean): List<Shop> = SHOPS.filter { s -> s.pkgs.any(has) }

    /** "Flipkart, Meesho या Amazon" */
    fun names(list: List<Shop>): String = when (list.size) {
        0 -> ""
        1 -> list[0].label
        else -> list.dropLast(1).joinToString(", ") { it.label } + " या " + list.last().label
    }

    fun question(itemHi: String, list: List<Shop>): String =
        "ठीक है, ${itemHi.ifBlank { "वह" }} दिखाता हूँ। किस ऐप पर दिखाऊँ - ${names(list)}?"

    /** जवाब ("फ्लिपकार्ट पे", "मीशो") -> ऐप. पहला / दूसरा भी */
    fun pick(reply: String, list: List<Shop>): Shop? {
        shopIn(reply)?.let { s -> return list.firstOrNull { it.key == s.key } ?: s }
        val t = reply.lowercase()
        return when {
            listOf("पहला", "पहले", "first").any { t.contains(it) } -> list.getOrNull(0)
            listOf("दूसरा", "दूसरे", "second").any { t.contains(it) } -> list.getOrNull(1)
            listOf("तीसरा", "तीसरे", "third").any { t.contains(it) } -> list.getOrNull(2)
            else -> null
        }
    }
}
