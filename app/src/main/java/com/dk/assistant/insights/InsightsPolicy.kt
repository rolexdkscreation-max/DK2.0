package com.dk.assistant.insights

/**
 * नवंबर 2026 (मालिक: "मोनेटाइज़ेशन नहीं मिला / हट गया तो क्यों, Facebook में रिकमेंडेशन बंद तो क्यों और कैसे चालू हो,
 * पॉलिसी और एल्गोरिदम भी बताए - पर झूठ कभी नहीं").
 *
 * सच का नियम: वजह सिर्फ़ वही, जो ऐप की स्क्रीन पर लिखी पढ़ी गई ([Account.monetization] / [Account.recommendation]).
 * यहाँ की बातें = कंपनी के अपने help पेज के नियम (लिंक साथ), ताकि वजह का मतलब और ठीक करने का रास्ता बताया जा सके.
 * स्क्रीन पर वजह न दिखी = साफ़ "पता नहीं चला" - अंदाज़ा नहीं.
 */
object InsightsPolicy {

    /** आख़िरी रील के कमेंट पढ़े (या रील पर 0 कमेंट दिखे) */
    fun commentsRead(d: PlatformData): Boolean = d.posts.any { it.commentList.isNotEmpty() || it.comments == 0L }

    data class Rule(val words: Regex, val why: String, val fix: String)

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    /** स्क्रीन पर लिखी बात -> उसका मतलब + ठीक करने का रास्ता (सिर्फ़ पढ़ी बात पर लगता है) */
    private val COMMON = listOf(
        Rule(rx("unoriginal|limited originality|repurposed|reposted|reused|duplicat|inauthentic|repetitious|mass.?produced|मूल नहीं"),
            "बिना बदलाव के दूसरों का / दोबारा डाला कंटेंट (unoriginal / reused) पकड़ा गया",
            "अपना बनाया या साफ़ बदला हुआ कंटेंट डालें (अपनी आवाज़, कमेंट्री, एडिट); दूसरे ऐप का watermark न हो; कुछ समय लगातार original रीलें डालकर ही review / appeal माँगें"),
        Rule(rx("community (standards|guidelines)|strike|violat|उल्लंघन"),
            "कम्युनिटी नियम (Community Standards / Guidelines) तोड़ने वाली पोस्ट या strike",
            "वह पोस्ट हटाएँ या गलत लगे तो वहीं 'Request review / Appeal' दबाएँ; आगे से वैसी चीज़ न डालें - strike समय के साथ हटती है"),
        Rule(rx("copyright|intellectual property|claim|कॉपीराइट"),
            "कॉपीराइट / किसी और का गाना-वीडियो बिना हक के",
            "ऐप की लाइब्रेरी वाला गाना या अपना ऑडियो लें; claim गलत हो तो dispute करें"),
        Rule(rx("misinformation|false (news|information)|clickbait|sensational|engagement bait|झूठी"),
            "गलत जानकारी / clickbait / 'लाइक करो-शेयर करो' जैसा engagement bait",
            "शीर्षक और कैप्शन सच्चे रखें, लाइक-शेयर माँगने वाली लाइनें हटाएँ"),
        Rule(rx("artificial|fake (followers|engagement|views)|inauthentic behavio|bought|purchased"),
            "नकली फ़ॉलोअर / व्यू / engagement (खरीदे या ऐप से)",
            "ऐसे ऐप / सेवा बंद करें; मोनेटाइज़ेशन सिर्फ़ असली दर्शकों की कमाई पर होता है"),
        Rule(rx("country|region|location|देश"),
            "देश / पेमेंट खाते का देश इस प्रोग्राम में नहीं",
            "प्रोफ़ाइल और payout खाते का देश सही रखें; प्रोग्राम आपके देश में न हो तो अभी चालू नहीं होगा"),
        Rule(rx("payout|tax|bank|adsense|पेमेंट"),
            "पेमेंट / टैक्स / AdSense की जानकारी अधूरी",
            "Payout (YouTube में AdSense) की जानकारी पूरी करें"),
        Rule(rx("age|18|उम्र"),
            "उम्र (18+) की शर्त पूरी नहीं",
            "खाते की जन्मतिथि सही हो; 18 साल से कम पर कई कमाई वाले टूल नहीं मिलते"),
        Rule(rx("inactive|inactivity|activity requirement|no uploads"),
            "चैनल / प्रोफ़ाइल पर लंबे समय से अपलोड नहीं (activity की शर्त)",
            "नियमित अपलोड शुरू करें - YouTube 1 फ़रवरी 2027 से हर 90 दिन में 2 लंबे वीडियो या 5 Shorts को active मानेगा"),
        Rule(rx("not (yet )?eligible|ineligible|can.?t be recommended|not recommend|at risk|restrict|limited"),
            "खाता / कंटेंट अभी पात्र नहीं (eligible नहीं) या रिकमेंडेशन रुका / खतरे में",
            "उसी पेज पर दिखी वजह (या 'See details') खोलें, गलत लगे तो 'Request review' करें; ठीक होने तक फ़ॉलोअर को पोस्ट दिखती रहती है"),
    )

    /** जो स्क्रीन पर लिखा पढ़ा गया, उसी की वजहें. कुछ नहीं पढ़ा = खाली (कोई अंदाज़ा नहीं) */
    fun reasons(text: String): List<Rule> {
        if (text.isBlank() || NOT_FOUND.containsMatchIn(text) || OK.containsMatchIn(text) && !BAD.containsMatchIn(text)) return emptyList()
        return COMMON.filter { it.words.containsMatchIn(text) }.take(3)
    }

    private val NOT_FOUND = rx("not found|नहीं मिला")
    private val OK = rx("able to monetize|able to monetise|eligible|no issues|no restrictions|ready to set up|in ypp|monetized|you.?re all set|recommended")
    private val BAD = rx("not (yet )?eligible|ineligible|can.?t|cannot|not recommend|violation|restrict|suspend|removed|limited|at risk|strike|demoneti")

    /** हालत एक लाइन में - "ठीक / दिक्कत / पता नहीं" (बिना पढ़े कभी "ठीक" नहीं) */
    fun verdict(text: String): String = when {
        text.isBlank() || NOT_FOUND.containsMatchIn(text) -> "पता नहीं चला (वह पेज स्क्रीन पर नहीं मिला)"
        BAD.containsMatchIn(text) -> "दिक्कत है"
        OK.containsMatchIn(text) -> "ठीक है"
        else -> "स्क्रीन पर लिखा था"
    }

    /** कंपनी के अपने नियम (हिंदी, छोटे में) + आधिकारिक लिंक - रिपोर्ट में "पॉलिसी" और "एल्गोरिदम" हिस्से के लिए */
    fun guide(p: Platform): String = when (p) {
        Platform.YOUTUBE -> """
            YouTube मोनेटाइज़ेशन (YPP): अभी 1,000 सब्सक्राइबर + पिछले 12 महीने में 4,000 पब्लिक watch hours, या 90 दिन में 1 करोड़ Shorts views.
            1 फ़रवरी 2027 से नए चैनल के लिए: 1,000 सब्सक्राइबर + 8,000 watch hours (365 दिन) या 2 करोड़ Shorts views (90 दिन); जो पहले से YPP में हैं उन पर असर नहीं.
            कम शर्त वाले टूल (fan funding, Shopping): 500 सब्सक्राइबर + 3,000 watch hours या 30 लाख Shorts views.
            मोनेटाइज़ेशन हटने की आम वजहें: reused content (दूसरों का कंटेंट बिना अपनी कमेंट्री / बड़े बदलाव के), inauthentic content (एक जैसे, mass-produced वीडियो),
            Community Guidelines strike, कॉपीराइट, और लंबे समय तक अपलोड न करना. यह पूरे चैनल पर लगता है, एक वीडियो पर नहीं.
            ठीक करना: खुद का original कंटेंट, Studio के Earn टैब में दिखी वजह पढ़ें; अर्ज़ी नामंज़ूर हो तो आम तौर पर 30 दिन बाद फिर अर्ज़ी.
            एल्गोरिदम: YouTube दर्शक की पसंद पर चलता है - कितने लोग क्लिक करते हैं (CTR), कितनी देर देखते हैं (watch time / average view duration),
            और देखने के बाद संतुष्ट रहते हैं या नहीं. Shorts में 'Shown in feed' के मुकाबले 'Chose to view' (रुककर देखना) ज़रूरी.
            लिंक: https://support.google.com/youtube/answer/72851 , https://support.google.com/youtube/answer/12843009 , https://support.google.com/youtube/answer/1311392
        """.trimIndent()
        Platform.INSTAGRAM -> """
            Instagram मोनेटाइज़ेशन: प्रोफ़ेशनल (creator/business) खाता, 18+, सही देश, Partner Monetization Policies + Content Monetization Policies मानना,
            payout सेटअप. हालत देखें: Menu -> Settings -> Creator / Business tools and controls -> Monetization status (वहीं 'Request a review').
            रिकमेंडेशन: Account Status में दिखता है कि कंटेंट non-followers को recommend हो सकता है या नहीं, और कौन सी पोस्ट / प्रोफ़ाइल फ़ोटो / bio नियम के खिलाफ है.
            Recommend न होने की आम वजहें: दूसरी जगह से थोड़े बदलाव के साथ दोबारा डाला कंटेंट (watermark), अश्लीलता की तरफ़ झुकी चीज़, हिंसा,
            गलत जानकारी, engagement bait, हाल में नियम तोड़ना. Recommend न हो तब भी फ़ॉलोअर को पोस्ट दिखती है.
            ठीक करना: Account Status में वह पोस्ट हटाएँ / बदलें या 'Request review' करें; आगे original कंटेंट.
            एल्गोरिदम (Instagram प्रमुख Adam Mosseri के अनुसार): तीन सबसे बड़े संकेत - watch time, likes per reach, sends (DM शेयर) per reach;
            non-followers तक पहुँचने में DM से भेजना सबसे ज़्यादा मायने रखता है. सही वज़न Instagram नहीं बताता.
            लिंक: https://help.instagram.com/653964212890722 , https://help.instagram.com/561796329332844
        """.trimIndent()
        Platform.FACEBOOK -> """
            Facebook मोनेटाइज़ेशन: Page या professional mode वाली प्रोफ़ाइल (आम प्रोफ़ाइल पर नहीं), सही देश, Community Standards + Partner Monetization Policies
            + Content Monetization Policies मानना. हालत: Creator / Business Dashboard (पहले Professional dashboard) -> Monetization -> 'Ready to set up' या
            'Not yet eligible'; desktop पर Meta Business Suite -> Monetization -> Policy issues.
            रिकमेंडेशन बंद होने की सबसे आम वजह: limited originality - unoriginal / दूसरों की दोबारा डाली रीलें बिना असली बदलाव (कमेंट्री, पैरोडी, क्रिएटिव एडिट).
            इससे कमाई और non-followers को recommend होना दोनों रुकते हैं. और: गलत जानकारी, clickbait, नियम तोड़ना.
            रिकमेंडेशन कैसे देखें: Menu -> Help & support -> Account Status (Page के लिए: Settings -> Page setup -> Page Status). वहाँ 'at risk' या 'not recommendable' लिखा होता है.
            चालू कैसे करें: वजह वाली पोस्ट हटाएँ / बदलें, काफ़ी सारी original रीलें डालें, फिर 'Request review / Appeal'. गलत लगे तो तुरंत appeal.
            एल्गोरिदम: Facebook original और देखी जाने वाली रीलें (watch time, शेयर, कमेंट) आगे बढ़ाता है; दोबारा डाला कंटेंट नीचे करता है.
            लिंक: https://business.facebook.com/business/help/169845596919485 , https://business.facebook.com/business/help/3382366608650437 , https://www.facebook.com/help/1392616391875085
        """.trimIndent()
    }

    /** प्रोफ़ाइल + कमाई + रिकमेंडेशन की सच्ची लाइनें (सिर्फ़ पढ़ा हुआ; न पढ़ा = "पता नहीं चला") */
    fun profileLines(d: PlatformData): List<String> {
        val a = d.account
        val out = mutableListOf<String>()
        val who = listOf(a.name, a.handle.let { if (it.isNotBlank() && !it.startsWith("@")) "@$it" else it }).filter { it.isNotBlank() }.joinToString(" ")
        out += "प्रोफ़ाइल: " + (who.ifBlank { "नाम स्क्रीन पर नहीं पढ़ा गया" }) +
            " | फ़ॉलोअर: " + (a.followers?.let { Num.short(it) } ?: "पता नहीं चला") +
            (a.following?.let { " | फ़ॉलोइंग: " + Num.short(it) } ?: "") +
            (a.postsCount?.let { " | पोस्ट: " + Num.short(it) } ?: "")
        if (a.lastPost.isNotBlank()) out += "आख़िरी ${d.platform.itemHi} कब डाली: ${a.lastPost}"
        d.posts.firstOrNull { it.commentList.isNotEmpty() }?.let { p ->
            out += "\"${p.title.take(40)}\" पर ${p.comments?.let { Num.short(it) } ?: p.commentList.size.toString()} कमेंट; कुछ: " + p.commentList.take(5).joinToString(" / ")
        }
        out += "मोनेटाइज़ेशन: ${verdict(a.monetization)}" + (if (a.monetization.isNotBlank() && !NOT_FOUND.containsMatchIn(a.monetization)) " - \"${a.monetization.take(160)}\"" else "")
        out += "रिकमेंडेशन: ${verdict(a.recommendation.ifBlank { a.status })}" + (a.recommendation.ifBlank { a.status }.takeIf { it.isNotBlank() }?.let { " - \"${it.take(160)}\"" } ?: "")
        (reasons(a.monetization) + reasons(a.recommendation.ifBlank { a.status })).distinct().take(3).forEach {
            out += "वजह (स्क्रीन पर लिखे के हिसाब से): ${it.why}. ठीक करने का रास्ता: ${it.fix}"
        }
        return out
    }
}
