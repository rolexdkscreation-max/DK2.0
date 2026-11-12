package com.dk.assistant

/**
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
     * यह notification छिपाना है (popup न आए, island में 🔒)?
     * सिर्फ़ चुने ऐप के, चलते (ongoing) / कॉल / गाना / DK के अपने नहीं - वे कभी नहीं छिपते.
     */
    fun hideNotif(on: Boolean, hideNotifs: Boolean, pkg: String, ownPkg: String, chosen: Set<String>,
                  ongoing: Boolean, isCall: Boolean, isMedia: Boolean): Boolean =
        on && hideNotifs && pkg != ownPkg && pkg in chosen && !ongoing && !isCall && !isMedia

    /** सेव किया हुआ "pkg1,pkg2" -> set (खाली/गलत छोड़कर) */
    fun parse(s: String?): Set<String> = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.contains('.') }.toSet()
    fun join(set: Set<String>): String = set.sorted().joinToString(",")
}
