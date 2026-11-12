package com.dk.assistant

/**
 * सेटिंग्स के ऊपर वाला सर्च (Android-free - JUnit से जाँचा जाता है).
 *
 * हर हिस्से (section) और उसके अंदर के हर कॉलम (card) का नाम + छोटा विवरण + कुछ आम शब्द.
 * user कुछ भी लिखे ("ऑटो", "alarm", "key", "आवाज़") -> मिलते हुए नतीजे; टैप = वही हिस्सा खुले और वही कॉलम खुलकर दिखे.
 * नया card जोड़ें तो यहाँ भी जोड़ें - SettingsSearchTest SettingsActivity.kt पढ़कर जाँचता है कि कोई card छूटा नहीं.
 */
object SettingsSearch {
    /** section = SettingsActivity का "section" (या ACT_* = सीधे अपनी screen); card = कॉलम का नाम (खाली = पूरा हिस्सा) */
    data class E(val section: String, val card: String, val sub: String = "", val words: String = "")

    const val ACT_AUTO = "act:auto"
    const val ACT_TEMPLATES = "act:templates"
    const val ACT_CONNECTORS = "act:connectors"
    const val ACT_STORY = "act:story"
    const val ACT_BACKUP = "act:backup"

    /** हिस्सों के नाम (मेनू की पंक्ति) */
    val SECTIONS: Map<String, String> = linkedMapOf(
        "profile" to "प्रोफ़ाइल", "lang" to "भाषा / Language", "ui" to "दिखावट (UI कस्टमाइज़ेशन)", "brain" to "AI दिमाग",
        "brain_live" to "AI दिमाग • DK लाइव", "brain_chat" to "AI दिमाग • DK चैट", "brain_coder" to "AI दिमाग • कोडिंग एजेंट",
        "brain_pc" to "AI दिमाग • DK कंप्यूटर", "brain_web" to "AI दिमाग • वेबसाइट बिल्डर", "voice" to "आवाज़", "orb" to "ओर्ब और चेहरा",
        "access" to "Accessibility", "phonelock" to "फ़ोन लॉक / अनलॉक", "guard" to "स्क्रीन गार्ड", "perms" to "परमिशन", "computer" to "DK कंप्यूटर", "coder" to "कोडिंग एजेंट",
        "social" to "सोशल रिव्यू", "reels" to "रील गिनती और लिमिट",
        ACT_AUTO to "ऑटोनोमस मोड", ACT_TEMPLATES to "टेम्पलेट कमांड", ACT_CONNECTORS to "कनेक्टर", ACT_STORY to "स्टोरी राइटर एजेंट", ACT_BACKUP to "बैकअप",
        // नवंबर 2026: ऑटो रिप्लाई (ऑटोनोमस मोड के बाद - "ऑटो" खोजने पर पहले ऑटोनोमस मोड ही आए)
        "autoreply" to "ऑटो रिप्लाई",
        "update" to "अपडेट", "debug" to "डिबग लॉग", "about" to "ऐप के बारे में",
        // नवंबर 2026: Control Center के 6 श्रेणी कार्ड (आख़िर में, ताकि पुराने नतीजों का क्रम न बदले)
        "cat_dk" to "मेरा DK", "cat_brain" to "दिमाग और आवाज़", "cat_auto" to "ऑटोमेशन",
        "cat_security" to "सुरक्षा", "cat_social" to "सोशल और कहानी", "cat_data" to "डेटा और ऐप"
    )

    /** नवंबर 2026: Control Center का मुख्य पेज = ये 6 कार्ड (id, नाम, छोटा विवरण, अंदर की पुरानी पंक्तियाँ - इसी क्रम में).
     *  हर पुरानी पंक्ति ठीक एक कार्ड में - CategoriesTest जाँचता है कि कोई छूटी नहीं. */
    val CATEGORIES: List<Pair<Triple<String, String, String>, List<String>>> = listOf(
        Triple("cat_dk", "मेरा DK", "प्रोफ़ाइल • भाषा • दिखावट • ओर्ब और चेहरा") to listOf("profile", "lang", "ui", "orb"),
        Triple("cat_brain", "दिमाग और आवाज़", "AI दिमाग • आवाज़ • कोडिंग एजेंट • DK कंप्यूटर") to listOf("brain", "voice", "coder", "computer"),
        Triple("cat_auto", "ऑटोमेशन", "ऑटोनोमस मोड • टेम्पलेट कमांड • ऑटो रिप्लाई • Accessibility") to listOf("auto", "templates", "autoreply", "access"),
        Triple("cat_security", "सुरक्षा", "फ़ोन लॉक • स्क्रीन गार्ड • परमिशन") to listOf("phonelock", "guard", "perms"),
        Triple("cat_social", "सोशल और कहानी", "सोशल रिव्यू • रील गिनती और लिमिट • स्टोरी राइटर") to listOf("social", "reels", "story"),
        Triple("cat_data", "डेटा और ऐप", "कनेक्टर • बैकअप • अपडेट • डिबग लॉग • ऐप के बारे में") to listOf("connectors", "backup", "update", "debug", "about")
    )

    /** हर हिस्से के आम शब्द (हिंदी + English + बोलचाल) - सर्च में मदद */
    private val SECTION_WORDS = mapOf(
        "profile" to "नाम name profile असिस्टेंट assistant",
        "lang" to "भाषा language hindi english हिंदी अंग्रेज़ी",
        "ui" to "theme थीम रंग color colour design look दिखावट animation",
        "brain" to "api key चाबी gemini ai दिमाग brain",
        "brain_live" to "live लाइव gemini key api voice search खोज tavily exa groq z.ai zai nvidia",
        "brain_chat" to "chat चैट key api openai groq openrouter z.ai zai glm nvidia एनवीडिया nim",
        "brain_coder" to "coder coding कोडिंग key api",
        "brain_pc" to "computer कंप्यूटर key api z.ai zai glm nvidia एनवीडिया nim",
        "brain_web" to "website वेबसाइट forge फोर्ज builder बिल्डर site साइट key api gemini मॉडल model",
        "voice" to "voice आवाज़ बोलना सुनना mic माइक tts whatsapp memory याददाश्त dost दोस्त",
        "orb" to "orb ओर्ब चेहरा",
        "access" to "accessibility फ़ोन कंट्रोल control battery बैटरी call कॉल",
        "guard" to "screen guard स्क्रीन गार्ड गार्ड मोड guard गार्ड security सुरक्षा chor चोर चोरी theft anti alarm siren सायरन intruder घुसपैठिया पहरा चौकीदार",
        "phonelock" to "lock unlock लॉक अनलॉक फ़ोन फोन phone pin पिन pattern पैटर्न password पासवर्ड screen स्क्रीन खोलो बंद",
        "perms" to "permission परमिशन इजाज़त mic camera notification",
        "computer" to "computer कंप्यूटर reminder रिमाइंडर search",
        "coder" to "coding कोडिंग github गिटहब agent एजेंट",
        "social" to "social सोशल review रिव्यू instagram इंस्टाग्राम facebook फेसबुक youtube यूट्यूब studio स्टूडियो reel रील views व्यूज़ reach रीच insights analytics trend ट्रेंड",
        "reels" to "reel रील reels रीलें video वीडियो week हफ़्ता हिसाब 7 दिन आवाज़ shorts शॉर्ट्स count गिनती limit लिमिट lock लॉक block ब्लॉक बंद instagram इंस्टाग्राम youtube यूट्यूब facebook फेसबुक popup पट्टी समय",
        ACT_AUTO to "autonomous auto ऑटो ऑटोनॉमस ऑटोनोमस अपने-आप खुद automatic आपातकालीन stop",
        ACT_TEMPLATES to "template टेम्पलेट साँचा command कमांड alarm अलार्म timer whatsapp",
        ACT_CONNECTORS to "connector कनेक्टर gmail drive calendar instagram facebook mcp",
        ACT_STORY to "story कहानी writer लेखक",
        ACT_BACKUP to "backup बैकअप restore रिस्टोर drive ड्राइव google गूगल email ईमेल याददाश्त memory बातचीत",
        "autoreply" to "auto reply ऑटो रिप्लाई autoreply जवाब अपने-आप खुद-ब-खुद whatsapp व्हाट्सएप business बिजनेस telegram टेलीग्राम messenger मैसेंजर message मैसेज signal sms instagram reply रिप्लाई",
        "update" to "update अपडेट version वर्ज़न",
        "debug" to "debug log लॉग गलती error",
        "about" to "about privacy निजता version"
    )

    val ENTRIES: List<E> = SECTIONS.keys.map { E(it, "", "", SECTION_WORDS[it].orEmpty()) } + listOf(
        E("autoreply", "ऑटो रिप्लाई कैसे काम करता है", "मैसेज आए तो DK आपकी ओर से, आपके ही ढंग में, सिर्फ़ उसी बात का जवाब"),
        E("autoreply", "ऑटो रिप्लाई चालू / बंद", "एक मुख्य स्विच — बंद = किसी भी ऐप में अपने-आप जवाब नहीं"),
        E("autoreply", "किन ऐप पर ऑटो रिप्लाई", "फ़ोन में मिले मैसेज वाले ऐप — हर एक का अपना स्विच (शुरू में बंद)"),
        E("autoreply", "आपका लिखने का ढंग", "DK ने आपके भेजे मैसेज से कितना सीखा • मिटाएँ"),
        E("social", "सोशल रिव्यू कैसे करें", "एक बार बोलें — DK खुद ऐप खोलकर हर रील / वीडियो के आँकड़े देखकर पूरी रिपोर्ट बताएगा"),
        E("social", "आँकड़े कहाँ से लें", "हर प्लेटफ़ॉर्म के लिए: दोनों (सबसे पक्का) • सिर्फ़ API • सिर्फ़ ऐप की स्क्रीन"),
        E("social", "ट्रेंड और आपका विषय", "रिपोर्ट में अभी के ट्रेंडिंग टॉपिक और आपके विषय के हिसाब से सलाह"),
        E("social", "ज़रूरी तैयारी", "कनेक्टर ज़रूरी नहीं — बस ये चीज़ें"),
        E("social", "पिछली रिपोर्ट", "आख़िरी सोशल रिव्यू की पूरी रिपोर्ट"),
        E("reels", "7 दिन का रील हिसाब", "आज, कल, हर दिन — कितनी रील, कितने मिनट, कम हुई या ज़्यादा"),
        E("reels", "सब ऐप की कुल रोज़ लिमिट और आवाज़", "जैसे 100 — पार हुई तो DK बोलकर बताएगा"),
        E("reels", "रील गिनती (ऊपर छोटी पट्टी)", "रील / शॉर्ट्स देखते समय ऊपर दिखे: आज कितनी रील देखीं"),
        E("reels", "हर ऐप की रील लिमिट", "रोज़ कितनी रील — पूरी होते ही फ़ोन लॉक या ऐप बंद"),
        E("reels", "लिमिट से बंद ऐप खोलें", "यहीं से खोलें — तब तक वह ऐप नहीं खुलेगा"),
        E("profile", "प्रोफ़ाइल", "आपका नाम, असिस्टेंट का नाम और बात करने का तरीका"),
        E("profile", "Access key", ""),
        E("lang", "ऐप की भाषा (स्क्रीन)", "पूरे ऐप के बटन, Settings, संदेश — English या हिंदी"),
        E("lang", "DK किस भाषा में जवाब दे", "DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर और कोडिंग एजेंट — सब पर एक साथ"),
        E("brain", "एक key से सब (ऑल-राउंडर)", "सिर्फ़ एक Gemini key — DK लाइव में बात, चैट, सुनना-लिखना, Google खोज और फ़ोन के काम। कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर, वेबसाइट के लिए इसे बंद करके अलग-अलग keys डालें"),
        E("brain", "अलग-अलग key वाले हिस्से — बंद", "ऊपर \"एक key से सब\" चालू है, इसलिए DK लाइव / चैट / कोडिंग / कंप्यूटर की अलग keys अभी इस्तेमाल नहीं हो रहीं"),
        E("brain", "हर हिस्से की अपनी API", "हर हिस्सा सिर्फ़ अपनी डाली हुई key से जवाब लेता है — किसी दूसरे की key इस्तेमाल नहीं करता"),
        E("brain_web", "वेबसाइट बिल्डर — अपनी Gemini key", "DK से जब भी वेबसाइट बनवाएँगे, सिर्फ़ यही key लगेगी — DK लाइव, चैट, कोडिंग या कोई दूसरी key नहीं"),
        E("brain_live", "गहरी सोच — Z.ai / NVIDIA, चाहें तो", "कठिन सवाल पर DK लाइव Z.ai (GLM) या NVIDIA की key से सोचकर बोलता है — आवाज़ Gemini Live की ही"),
        E("brain_live", "DK लाइव — Google Gemini", "Gemini Live बातचीत, Gemini नेचुरल आवाज़ और सुनना — सिर्फ़ इसी key से"),
        E("brain_live", "लिखना (\"आप:\" वाली लाइन) — अपनी अलग Gemini key", "आप जो बोलते हैं उसे लिखने वाला (gemini-3.5-transcribe-live) इसी key से चलेगा, ताकि बोलने वाले Live की free limit इसमें खर्च न हो"),
        E("brain_live", "खोज — Tavily, Exa, Gemini (बैकअप क्रम)", "DK लाइव और चैट की इंटरनेट खोज पहले Tavily से; न चले तो अपने-आप Exa; वह भी न चले तो Gemini + Google Search"),
        E("brain_live", "सुनना (आवाज़ → text) — Groq, चाहें तो", "Groq Whisper सबसे तेज़ सुनता है। खाली छोड़ें = ऊपर वाली Gemini key से सुनेगा"),
        E("brain_chat", "DK चैट की AI", "चैट, आम जवाब, बिना-Live आवाज़ मोड का जवाब, search, गहरी सोच और कहानी — सिर्फ़ इसी की key से"),
        E("brain_chat", "तुरंत सेटअप — कोई भी फ्री key डालें", "DK खुद पहचानेगा कि key किस कंपनी की है, उसके फ्री मॉडल जाँचकर सबसे तेज़ चुनेगा और बात शुरू कर देगा"),
        E("voice", "JARVIS मोड", "JARVIS जैसी आवाज़ और अंदाज़ — काम सारे वही जो DK करता है. बोलें: \"जार्विस मोड ऑन करो\" / \"डीके मोड ऑन करो\""),
        E("voice", "DK का स्वभाव", "दोस्ताना / नटखट शरारती / गर्लफ्रेंड / प्रोफ़ेशनल / शांत — मूड समझकर बात, बगल की बातचीत (सुनेगी, जवाब नहीं)", "persona natkhat naughty professional girlfriend mood side talk personality स्वभाव मूड"),
        E("voice", "बोलने वाला इंजन (DK Live)", "DK लाइव (Direct Gemini Live Voice, डिफ़ॉल्ट) • Gemini TTS (बैकअप) — एक समय में एक"),
        E("voice", "सुनना (माइक)", "लगातार सुनना — बिना बीप की आवाज़"),
        E("voice", "Hey DK (फ़ोन के अंदर)", "ऐप बंद हो तब भी \"Hey DK\" बोलिए — DK \"हाँ सर\" बोलकर सुनने लगेगा. इंटरनेट पर कुछ नहीं जाता"),
        E("voice", "WhatsApp संदेश", "\"राहुल को WhatsApp पर लिखो …\" कहने पर DK खुद भेजता है और भेजने के बाद ही \"भेज दिया\" बोलता है"),
        E("voice", "टेम्पलेट कमांड", "\"व्हाट्सएप ओपन करो ___ को ___ मैसेज भेज दो\" जैसे साँचे — बिना API key, बिना AI"),
        E("voice", "ऑटोनोमस मोड", "DK खुद तय करे कि काम कोडिंग का है या फ़ोन के ऐप का, और खुद करे — शुरू में बंद"),
        E("voice", "Memory (याददाश्त)", "क्या-क्या सेव है देखें, कोई एक चीज़ या सारी मेमोरी मिटाएँ — सब सिर्फ़ इसी फ़ोन में"),
        E("voice", "सच्चा दोस्त", "DK करीबी दोस्त की तरह अपनेपन से बात करे, पिछली बातें याद रखे"),
        E("orb", "झलक", "ओर्ब पर टैप = चेहरा बनने वाला एनीमेशन"),
        E("orb", "ओर्ब का आकार", "सब छोटे-छोटे 3D डॉट से बने हैं — जो पसंद हो चुनें"),
        E("orb", "किनारों की चमक", "DK सुन या बोल रहा हो तो स्क्रीन के किनारे चमकें"),
        E("orb", "DK का चेहरा", "\"अपना चेहरा दिखाओ\" बोलने पर डॉट निकलकर चेहरा बनता है — आवाज़ महिला हो तो महिला चेहरा, पुरुष हो तो पुरुष"),
        E("orb", "DK island (iPhone जैसा)", "DK से बाहर हों तो ऊपर काली गोली - टैप पर हाल के 5 काम, कॉल, YouTube / गाना"),
        E("ui", "झलक", "बदलाव यहीं तुरंत दिखता है"),
        E("ui", "वॉलपेपर", "पूरे ऐप के पीछे का चित्र • wallpaper • बैकग्राउंड • background • पृष्ठभूमि बदलें • गैलरी से अपना फोटो • कस्टम • custom • नीला हटाएँ"),
        E("ui", "थीम गैलरी", "पूरी थीम: पृष्ठभूमि चित्र, काँच, रंग और ओर्ब • डाउनलोड • लागू करें • Cyber Neon • Galaxy • Emerald • Solar Gold"),
        E("ui", "थीम", "पृष्ठभूमि का रंग"),
        E("ui", "मुख्य रंग (Accent)", "किनारे, चमक, मुख्य बटन और ओर्ब का रंग"),
        E("ui", "काँच और चमक", "पैनल कितने पारदर्शी हों, किनारों की नियॉन चमक कितनी हो"),
        E("ui", "तैरता ORB", "बोलते-सुनते समय दिखने वाला ORB — उंगली से कहीं भी खिसकाओ, आकार छोटा/बड़ा"),
        E("ui", "एनिमेशन", "ओर्ब के छल्ले, पृष्ठभूमि की रोशनी और मेनू का खुलना"),
        E("ui", "टाइपिंग एनीमेशन", "जवाब आने से पहले चैट के बुलबुले में क्या चले — जो पसंद हो चुनें"),
        E("ui", "दिखावट पहले जैसी करें", "सिर्फ़ रंग-रूप शुरुआती जैसा — बातचीत, यादें, key और आवाज़ की सेटिंग नहीं मिटतीं"),
        E("access", "फ़ोन कंट्रोल (Accessibility)", "एक बार ON करें — फिर ON ही रहेगा"),
        E("access", "बार-बार बंद न हो", "Android बैटरी बचाने के लिए DK को बंद कर देता है — यह रोकें"),
        E("access", "सुनना और कॉल", ""),
        E("access", "गड़बड़ी रिपोर्ट", "पिछली बार DK किस वजह से बंद हुआ"),
        E("guard", "स्क्रीन गार्ड", "कोई और फ़ोन छुए / उठाए तो सायरन + \"यह फ़ोन आपका नहीं है\""),
        E("guard", "गुप्त शब्द और भरोसेमंद नंबर", "बोलकर सायरन बंद • चोरी पर SMS जगह के साथ"),
        E("guard", "मालिक का चेहरा", "पहली बार एक बार चेहरा सेट करें — अलार्म में कैमरा मालिक को पहचानकर सायरन बंद करे"),
        E("guard", "कितना संवेदनशील", "उठाने / छूने पर कब बजे — कम • मध्यम • ज़्यादा"),
        E("guard", "और सुरक्षा", "छूने वाले की फ़ोटो • मालिक का चेहरा • पावर मेनू रोकना • चार्जर • SIM"),
        E("guard", "घुसपैठिए का रिकॉर्ड", "छूने वाले की फ़ोटो और क्या-क्या हुआ"),
        E("guard", "प्राइवेसी स्क्रीन (बगल से न दिखे)", "Samsung S26 Ultra जैसा privacy display • पूरा फ़ोन या चुने ऐप • उनके notification popup भी छिपें"),
        E("phonelock", "आवाज़ से फ़ोन लॉक / अनलॉक", "\"हे DK फोन लॉक करो\" • \"हे मायरा फोन अनलॉक करो\" — बिना AI, सीधे फ़ोन पर"),
        E("phonelock", "क्या बोलें", "बुलाने के नाम (DK, मायरा, जारवी…) और लॉक / अनलॉक के वाक्य"),
        E("phonelock", "फ़ोन का PIN / पैटर्न / पासवर्ड", "एक बार डालें — फ़ोन के अंदर ताले (Keystore) में सेव, कहीं भेजा नहीं जाता"),
        E("perms", "स्क्रीन देखना", "DK आपकी स्क्रीन देखकर काम करे (स्क्रीनशॉट AI को जाता है)"),
        E("perms", "ज़रूरी परमिशन", ""),
        E("update", "अपडेट", ""),
        E("debug", "DK Inspector", "Run Deep Inspection • Ask DK Inspector • Bug Reports • हर 20 मिनट अपने-आप जाँच", "inspector bug report जाँच गड़बड़ी debugger"),
        E("debug", "Gemini Live हाल", "माइक से भेजे टुकड़े, गिरे टुकड़े, दोबारा जुड़ना, देरी"),
        E("debug", "देरी का हिसाब", "आपका बोलना ख़त्म → पहला टुकड़ा आया → पहले वाक्य की आवाज़ बनी → आवाज़ बजी"),
        E("debug", "Live / Text हिसाब", "Gemini Live, text मोड (बैकअप) और पीछे की सेहत-जाँच - हर एक की कॉल, ठीक, गलती अलग", "live text fallback backup health बैकअप सेहत"),
        E("debug", "लॉग", "सबसे नई लाइन नीचे • 300 लाइन तक"),
        E("coder", "कोडिंग एजेंट", "आपके GitHub repo में कोड लिखता, बदलता, हटाता और push करता है"),
        E("coder", "GitHub कनेक्टर", "Personal Access Token से जुड़ता है (फ़ोन में ही रहता है)"),
        E("computer", "DK कंप्यूटर", "कोई भी काम कदम-दर-कदम: इंटरनेट पर खोज, वेबसाइट पढ़ना, हिसाब (कोड), फ़ाइल/PDF, फ़ोन के काम, कोडिंग, रिमाइंडर"),
        E("computer", "वेब search", "Gemini key से Google search फ्री; Tavily / Brave की key डालें तो उनसे (Groq/OpenRouter वालों के लिए भी)"),
        E("computer", "कंप्यूटर के टूल", "जो बंद करेंगे, वह DK कंप्यूटर इस्तेमाल नहीं करेगा"),
        E("computer", "तय किए काम", "रिमाइंडर और बाद में चलने वाले काम — फ़ोन restart होने पर भी याद रहते हैं"),
        E("about", "निजता (Privacy)", "आपका डेटा कहाँ जाता है"),
        E("about", "और विकल्प", ""),
    )

    /** हिंदी लिखावट के छोटे फ़र्क मिटाओ (ज़/ज, ँ/ं, ो/ॉ...) ताकि बोलकर/जल्दी लिखा भी मिले */
    private const val PRE = "कखगजडढफय"      // क़-य़ (क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़ एक ही अक्षर वाले) -> बिना नुक़्ते
    fun norm(s: String): String = s.lowercase()
        .map { ch -> if (ch.code in 0x0958..0x095F) PRE[ch.code - 0x0958] else ch }.joinToString("")
        .replace("\u093C", "")            // नुक़्ता
        .replace('ँ', 'ं').replace('ॉ', 'ो').replace('ॅ', 'े').replace('ऑ', 'ओ').replace('ऍ', 'ए')
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    /** हर शब्द कहीं न कहीं मिले; नाम में मिले तो ऊपर. खाली = कुछ नहीं */
    fun search(q: String, limit: Int = 30): List<E> {
        val words = norm(q).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return ENTRIES.mapNotNull { e ->
            val name = norm(e.card.ifBlank { SECTIONS[e.section].orEmpty() })
            val hay = norm(listOf(e.card, e.sub, SECTIONS[e.section].orEmpty(), e.words, SECTION_WORDS[e.section].orEmpty()).joinToString(" "))
            if (words.all { hay.contains(it) }) e to (if (words.all { name.contains(it) }) 0 else 1) * 10 + (if (e.card.isBlank()) 0 else 1) else null
        }.sortedBy { it.second }.map { it.first }.take(limit)
    }

    /** नतीजे की पंक्ति: "कॉलम" + "हिस्सा" */
    fun place(e: E): String = SECTIONS[e.section].orEmpty()
}
