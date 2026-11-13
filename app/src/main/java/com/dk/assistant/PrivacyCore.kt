package com.dk.assistant

/**
 * 13 नवंबर 2026 (मालिक): अब सिर्फ़ notification वाला ताला - [lockNotif]. स्क्रीन की परत हटा दी (काम नहीं करती थी).
 *
 * मालिक (12 नवंबर 2026): "Samsung S26 Ultra वाला Privacy Display DK में जोड़ो - चालू हो तो बगल से देखने वाले को स्क्रीन न दिखे,
 * सिर्फ़ सामने वाले को. किसी ख़ास ऐप पर भी लगे (फ़ोन के सारे ऐप की लिस्ट, हर एक का स्विच); उस ऐप का notification भी बगल से बिल्कुल न दिखे."
 *
 * S26 Ultra में यह screen के pixels (hardware) से होता है. दूसरे फ़ोन में वैसा hardware नहीं - DK software से सबसे पास वाला करता है:
 * पूरी स्क्रीन पर गहरी "प्राइवेसी परत" (बीच साफ़, किनारे और गहरे; बारीक धारियाँ) जिससे सामने से पढ़ा जाए पर तिरछे से रोशनी बहुत कम.
 * चुने ऐप के notification का popup ही नहीं आता - DK उसे छिपाकर island में सिर्फ़ "🔒 ऐप • N नए" रखता है (टैप = वही ऐप/चैट खुले).
 *
 * यह फ़ाइल सिर्फ़ नियम है (Android import नहीं -> JVM test).
 */
object PrivacyCore {

    /**
     * 13 नवंबर 2026 (मालिक: "स्क्रीन वाला हटा दो, सिर्फ़ नोटिफिकेशन वाला रखो - सभी ऐप या एक-एक ऐप"):
     * किन ऐप के notification ताले में जाएँ - सभी ऐप / सिर्फ़ चुने ऐप.
     */
    enum class Scope(val id: String, val hi: String) {
        ALL("all", "सभी ऐप"),
        APPS("apps", "सिर्फ़ चुने ऐप");
        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: APPS }
    }

    /** परत कितनी गहरी (%) - बहुत कम = कोई फ़र्क नहीं, बहुत ज़्यादा = सामने से भी न दिखे */
    const val MIN_STRENGTH = 20
    const val MAX_STRENGTH = 80
    const val DEF_STRENGTH = 50
    fun clampStrength(p: Int) = p.coerceIn(MIN_STRENGTH, MAX_STRENGTH)

    /** notification आने पर ऊपर का हिस्सा (popup वाली जगह) इतनी देर ढका रहे */
    const val POPUP_SHIELD_MS = 6_000L

    /**
     * अभी पूरी स्क्रीन पर परत लगे?
     * [fgPkg] = अभी सामने वाला ऐप (accessibility से). DK का अपना ऐप भी गिना जाता है अगर वह चुना हो.
     */
    fun shieldNow(on: Boolean, scope: Scope, fgPkg: String?, chosen: Set<String>): Boolean = when {
        !on -> false
        scope == Scope.ALL -> true
        fgPkg.isNullOrBlank() -> false
        else -> fgPkg in chosen
    }

    /**
     * यह notification छिपाना है (popup न आए, island में 🔒)?
     * सिर्फ़ चुने ऐप के, चलते (ongoing) / कॉल / गाना / DK के अपने नहीं - वे कभी नहीं छिपते.
     */
    fun hideNotif(on: Boolean, hideNotifs: Boolean, pkg: String, ownPkg: String, chosen: Set<String>,
                  ongoing: Boolean, isCall: Boolean, isMedia: Boolean): Boolean =
        on && hideNotifs && pkg != ownPkg && pkg in chosen && !ongoing && !isCall && !isMedia

    /** फ़ोन के अपने ज़रूरी notification (बैटरी, USB, सिस्टम) - "सभी ऐप" में भी ताले में नहीं जाते */
    val SYSTEM = setOf("android", "com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.samsung.android.incallui")

    /**
     * 13 नवंबर 2026: यह notification ताले में जाए (ऊपर से न दिखे, popup न आए; island में 🔒 - ऐप खोलें तब ही दिखे)?
     * [scope] = सभी ऐप / सिर्फ़ चुने ऐप. चलते (ongoing) / कॉल / गाना / DK के अपने / सिस्टम के कभी नहीं.
     */
    fun lockNotif(on: Boolean, scope: Scope, pkg: String, ownPkg: String, chosen: Set<String>,
                  ongoing: Boolean, isCall: Boolean, isMedia: Boolean): Boolean =
        on && pkg.isNotBlank() && pkg != ownPkg && pkg !in SYSTEM && (scope == Scope.ALL || pkg in chosen) && !ongoing && !isCall && !isMedia

    // ---- नीचे के नियम पुरानी स्क्रीन-परत (privacy display) के थे; 13 नवंबर 2026 से वह परत ऐप में नहीं लगती
    //      (मालिक: "स्क्रीन वाला काम नहीं करता, हटा दो"). पुराने टेस्ट के लिए नियम वैसे ही रखे हैं.

    /** notification आने पर ऊपर का हिस्सा ढको? (popup वाली जगह, Samsung का "Notification pop-ups") */
    fun shieldPopup(on: Boolean, popups: Boolean, pkg: String, ownPkg: String, ongoing: Boolean): Boolean =
        on && popups && pkg != ownPkg && !ongoing

    /** परत की गहराई (0..255): बीच हल्का, किनारे [edge] गुना गहरे - तिरछे से देखने वाले को किनारे से कुछ न दिखे */
    fun alphaCenter(strength: Int): Int = (clampStrength(strength) * 255 / 100 * 0.55f).toInt().coerceIn(0, 255)
    fun alphaEdge(strength: Int): Int = (clampStrength(strength) * 255 / 100 * 1.25f).toInt().coerceIn(0, 245)

    /** सेव किया हुआ "pkg1,pkg2" -> set (खाली/गलत छोड़कर) */
    fun parse(s: String?): Set<String> = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.contains('.') }.toSet()
    fun join(set: Set<String>): String = set.sorted().joinToString(",")
}
