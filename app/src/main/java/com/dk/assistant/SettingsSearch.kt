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
        "theme" to "Theme (थीम)", "profile" to "प्रोफ़ाइल", "lang" to "भाषा / Language", "ui" to "दिखावट (UI कस्टमाइज़ेशन)", "brain" to "AI दिमाग",
        "brain_live" to "AI दिमाग • DK लाइव", "brain_chat" to "AI दिमाग • DK चैट", "brain_coder" to "AI दिमाग • कोडिंग एजेंट",
        "brain_pc" to "AI दिमाग • DK कंप्यूटर", "voice" to "आवाज़", "orb" to "ओर्ब और चेहरा",
        "access" to "Accessibility", "phonelock" to "फ़ोन लॉक / अनलॉक", "guard" to "टच मोड", "applock" to "ऐप लॉक", "face" to "चेहरा पहचान", "perms" to "परमिशन", "computer" to "DK कंप्यूटर", "coder" to "कोडिंग एजेंट",
        "social" to "सोशल रिव्यू", "reels" to "रील गिनती और लिमिट",
        ACT_AUTO to "ऑटोनोमस मोड", ACT_TEMPLATES to "टेम्पलेट कमांड", ACT_CONNECTORS to "कनेक्टर", ACT_STORY to "स्टोरी राइटर एजेंट", ACT_BACKUP to "बैकअप",
        // नवंबर 2026: ऑटो रिप्लाई (ऑटोनोमस मोड के बाद - "ऑटो" खोजने पर पहले ऑटोनोमस मोड ही आए)
        "autoreply" to "ऑटो रिप्लाई",
        "update" to "अपडेट", "debug" to "डिबग लॉग", "about" to "ऐप के बारे में",
        // 17 नवंबर 2026: DK बैकएंड का लाइसेंस
        "license" to "लाइसेंस की",
        // 19 नवंबर 2026: फ़ोन पर बनने वाली नई आवाज़ें (Supertonic 3) - "आवाज़" के कार्ड से भी खुलता है
        "st3" to "आवाज़ें (Supertonic 3)",
        // नवंबर 2026: Control Center के 6 श्रेणी कार्ड (आख़िर में, ताकि पुराने नतीजों का क्रम न बदले)
        // 20 नवंबर 2026 (मालिक): "मेरा DK" = प्रोफ़ाइल • नया DK लाइव पैनल • आवाज़ दिमाग से अलग
        "persona" to "DK का स्वभाव और मोड", "memory" to "याददाश्त और सच्चा दोस्त",
        "cat_dk" to "प्रोफ़ाइल", "cat_live" to "DK लाइव", "cat_voice" to "आवाज़", "cat_brain" to "AI दिमाग", "cat_auto" to "ऑटोमेशन",
        "cat_security" to "सुरक्षा", "cat_social" to "सोशल और कहानी", "cat_data" to "डेटा और ऐप",
        // 22 नवंबर 2026 (मालिक): DK लाइव के अंदर लाइव वॉलपेपर
        "livewall" to "लाइव वॉलपेपर"
    )

    /** नवंबर 2026: Control Center का मुख्य पेज = ये 6 कार्ड (id, नाम, छोटा विवरण, अंदर की पुरानी पंक्तियाँ - इसी क्रम में).
     *  हर पुरानी पंक्ति ठीक एक कार्ड में - CategoriesTest जाँचता है कि कोई छूटी नहीं. */
    val CATEGORIES: List<Pair<Triple<String, String, String>, List<String>>> = listOf(
        Triple("cat_dk", "प्रोफ़ाइल", "आपका और DK का नाम • भाषा • दिखावट • ओर्ब और चेहरा") to listOf("profile", "lang", "ui", "orb"),
        Triple("cat_live", "DK लाइव", "JARVIS • FRIDAY / VENOM • DK का स्वभाव • याददाश्त • सच्चा दोस्त") to listOf("persona", "memory"),
        Triple("cat_voice", "आवाज़", "बोलने वाला इंजन • सुनना • वेक वर्ड • आवाज़ें (Supertonic 3) • वॉइस गार्डियन") to listOf("voice", "st3"),
        Triple("cat_brain", "AI दिमाग", "AI दिमाग • कोडिंग एजेंट • DK कंप्यूटर") to listOf("brain", "coder", "computer"),
        Triple("cat_auto", "ऑटोमेशन", "ऑटोनोमस मोड • टेम्पलेट कमांड • ऑटो रिप्लाई • Accessibility") to listOf("auto", "templates", "autoreply", "access"),
        Triple("cat_security", "सुरक्षा", "फ़ोन लॉक • टच मोड • ऐप लॉक • चेहरा पहचान • परमिशन") to listOf("phonelock", "guard", "applock", "face", "perms"),
        Triple("cat_social", "सोशल और कहानी", "सोशल रिव्यू • रील गिनती और लिमिट • स्टोरी राइटर") to listOf("social", "reels", "story"),
        Triple("cat_data", "डेटा और ऐप", "कनेक्टर • बैकअप • डिबग लॉग • ऐप के बारे में") to listOf("connectors", "backup", "debug", "about")
    )

    /** हर हिस्से के आम शब्द (हिंदी + English + बोलचाल) - सर्च में मदद */
    private val SECTION_WORDS = mapOf(
        "theme" to "theme थीम रंग color dark light clay day midnight nocturne ember abyss navy obsidian rosewood",
        "profile" to "नाम name profile असिस्टेंट assistant",
        "lang" to "भाषा language hindi english हिंदी अंग्रेज़ी",
        "ui" to "design look दिखावट animation orb dark light डार्क लाइट",
        "brain" to "api key चाबी gemini ai दिमाग brain",
        "brain_live" to "live लाइव gemini key api voice search खोज tavily exa groq z.ai zai nvidia",
        "brain_chat" to "chat चैट key api openai groq openrouter z.ai zai glm nvidia एनवीडिया nim",
        "brain_coder" to "coder coding कोडिंग key api",
        "brain_pc" to "computer कंप्यूटर key api z.ai zai glm nvidia एनवीडिया nim",
        "voice" to "voice आवाज़ बोलना सुनना mic माइक tts whatsapp",
        "persona" to "jarvis जार्विस friday फ्राइडे venom वेनम स्वभाव persona पर्सोना mood मूड girlfriend गर्लफ्रेंड romantic रोमांटिक नटखट professional संबोधन",
        "memory" to "memory मेमोरी याददाश्त याद dost दोस्त सच्चा दोस्त incognito इनकॉग्निटो पुरानी बातचीत",
        "orb" to "orb ओर्ब चेहरा",
        "access" to "accessibility फ़ोन कंट्रोल control battery बैटरी call कॉल",
        "applock" to "app lock ऐप लॉक एप लॉक applock lock लॉक ताला पिन pin pattern पैटर्न fingerprint फ़िंगरप्रिंट फिंगरप्रिंट face फ़ेस फेस password पासवर्ड locker लॉकर hide छिपाना private",
        "guard" to "touch mode टच मोड screen guard स्क्रीन गार्ड गार्ड मोड guard गार्ड security सुरक्षा chor चोर चोरी theft anti alarm siren सायरन intruder घुसपैठिया पहरा चौकीदार",
        // 15 नवंबर 2026 (मालिक): चेहरा पहचान वापस (v3.99 में हटाया था)
        "face" to "face फ़ेस फेस चेहरा पहचान recognition रजिस्ट्रेशन registration boss बॉस कौन who camera कैमरा परिवार watchman वॉचमैन",
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
        "license" to "license लाइसेंस की key चाबी premium प्रीमियम basic बेसिक free मुफ़्त whatsapp व्हाट्सएप login लॉगिन google गूगल admin मालिक",
        "debug" to "debug log लॉग गलती error",
        "about" to "about privacy निजता version",
        "livewall" to "live wallpaper लाइव वॉलपेपर wallpaper वॉलपेपर वालपेपर background बैकग्राउंड होम स्क्रीन लॉक स्क्रीन lock screen home sunrise sunset सूर्योदय सूर्यास्त सूरज चाँद चांद moon तारे stars आसमान sky",
        "st3" to "supertonic सुपरटोनिक आवाज़ें voices महिला female offline ऑफ़लाइन फ़ोन पर tts डाउनलोड download f1 टेस्ट वाक्य"
    )

    val ENTRIES: List<E> = SECTIONS.keys.map { E(it, "", "", SECTION_WORDS[it].orEmpty()) } + listOf(
        E("livewall", "लाइव वॉलपेपर • देखें और लगाएँ", "असली समय और जगह से सूरज, चाँद, तारे • डाउनलोड • लगाएँ (होम + लॉक)", "wallpaper वॉलपेपर download डाउनलोड apply लगाएँ preview"),
        E("livewall", "कहाँ लगाना है", "होम स्क्रीन + लॉक स्क्रीन दोनों, या सिर्फ़ होम", "lock लॉक home होम screen स्क्रीन"),
        E("livewall", "वॉलपेपर की बैटरी बचत", "लाइव वॉलपेपर: खुलते समय चलता, फिर रुकी तस्वीर • हमेशा चलता", "battery बैटरी save बचत animation"),
        E("livewall", "जगह (सूरज-चाँद का सही समय)", "आपके शहर का सूर्योदय-सूर्यास्त • मेरी जगह लें", "location जगह शहर city gps"),
        E("livewall", "लाइव वॉलपेपर कैसे काम करता है", "असली खगोल गणना • भौतिकी से आसमान • बैटरी", "battery बैटरी nasa"),
        E("autoreply", "ऑटो रिप्लाई कैसे काम करता है", "मैसेज आए तो DK आपकी ओर से, आपके ही ढंग में, सिर्फ़ उसी बात का जवाब"),
        E("autoreply", "ऑटो रिप्लाई चालू / बंद", "एक मुख्य स्विच — बंद = किसी भी ऐप में अपने-आप जवाब नहीं"),
        E("autoreply", "किन ऐप पर ऑटो रिप्लाई", "फ़ोन में मिले मैसेज वाले ऐप — हर एक का अपना स्विच (शुरू में बंद)"),
        E("autoreply", "आपका लिखने का ढंग", "DK ने आपके भेजे मैसेज से कितना सीखा • मिटाएँ"),
        E("social", "DK कंटेंट राइटिंग", "कहानी (पहले जैसी) + फैक्ट्स / स्क्रिप्ट / कैप्शन + हैशटैग / सुधार / फ़ोटो से", "content writing कंटेंट राइटिंग script स्क्रिप्ट facts फैक्ट्स caption कैप्शन hashtag हैशटैग story स्टोरी कहानी writer"),
        E("social", "सोशल पोस्ट स्पेशलिस्ट", "बोलकर पोस्ट: गैलरी से खुद चुने, देखकर कैप्शन/हैशटैग, कहने पर गाना/टैग, YouTube Shorts", "social specialist instagram facebook youtube shorts कैप्शन caption हैशटैग hashtag गाना song टैग tag गैलरी gallery पोस्ट post रील reel"),
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
        E("profile", "आपकी और जानकारी", "नंबर • पसंद के गाने (लिंक या नाम) • Instagram / Facebook / YouTube • अपने बारे में — DK को सब पता, कहें तो खोले",
            "number नंबर mobile मोबाइल phone फ़ोन song गाना गाने music instagram इंस्टाग्राम facebook फेसबुक youtube यूट्यूब handle हैंडल id आईडी link लिंक details जानकारी"),
        E("lang", "ऐप की भाषा (स्क्रीन)", "पूरे ऐप के बटन, Settings, संदेश — English या हिंदी"),
        E("lang", "DK किस भाषा में जवाब दे", "DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर और कोडिंग एजेंट — सब पर एक साथ"),
        E("brain", "एक key से सब (ऑल-राउंडर)", "सिर्फ़ एक Gemini key — DK लाइव में बात, चैट, सुनना-लिखना, Google खोज और फ़ोन के काम। कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर, वेबसाइट के लिए इसे बंद करके अलग-अलग keys डालें"),
        E("persona", "रोमांटिक (18+)", "DK का स्वभाव में: सहमति के बाद प्यार भरी, सभ्य बात; रुको / बंद करो = तुरंत बंद", "romantic 18+ adult रोमांटिक गर्लफ्रेंड"),
        E("voice", "आवाज़ें (फ़ोन पर बनने वाली नई आवाज़)", "महिला आवाज़ें 1-5 • डाउनलोड • टेस्ट वाक्य", "offline ऑफ़लाइन नई आवाज़ voices tts"),
        E("st3", "1. मुख्य आवाज़-मॉडल (एक बार)", "डाउनलोड / रोकें / हटाएँ • SHA-256 जाँच", "model मॉडल download डाउनलोड"),
        E("st3", "2. आवाज़ें (महिला)", "आवाज़ 1-5 • डाउनलोड करके चुनें", "voice f1 f2 f3 f4 f5 चुनें"),
        E("st3", "3. रफ़्तार, साफ़ी और टेस्ट", "रफ़्तार • कदम (steps) • टेस्ट वाक्य सुनाओ", "speed रफ़्तार steps कदम test टेस्ट"),
        E("voice", "बातचीत का तरीका (सुनना अलग, सोचना-बोलना अलग)", "Gemini Live या अलग-अलग तरीका • Whisper (फ़ोन पर) / Android पहचान • चुप्पी का समय • बीच में टोकना • Whisper मॉडल डाउनलोड / रोकें / हटाएँ", "whisper stt speech to text व्हिस्पर पहचान लिखना split अलग तरीका silence चुप्पी vad download डाउनलोड model मॉडल barge टोकना"),
        E("voice", "वॉइस गार्डियन", "फ़ोन पर ही आवाज़-पहचान: मॉडल डाउनलोड, 6 वाक्य सिखाना, 15 सैंपल वाली पक्की पहचान, पक्की रोक, सीमा, सिर्फ़ Boss की आवाज़, Away मोड", "voice guardian speaker verification owner away आवाज़ पहचान 15 सैंपल voice samples पक्की पहचान पक्की रोक strict"),
        E("voice", "परिवार की आवाज़ें और अवेयरनेस मोड", "मम्मी, पापा, भैया या कोई भी नाम - आवाज़ सिखाओ • कौन बोल रहा है (बॉस / परिवार / अनजान) • अवेयरनेस मोड (शुरू में बंद)", "family परिवार awareness अवेयरनेस speaker identification कौन बोल रहा mummy papa bhaiya मम्मी पापा भैया"),
        E("brain", "DK मास्टर एजेंट", "केंद्रीय दिमाग: प्लान दिखाए, पैसे / पासवर्ड / OTP पर रुककर पूछे, हर काम का लॉग", "master agent मास्टर एजेंट universal action plan प्लान log लॉग booking बुकिंग shopping शॉपिंग form फ़ॉर्म browser ब्राउज़र"),
        E("brain", "टूल एजेंट (Retry Loop)", "लंबे फ़ोन-काम: Gemini खुद tool चुने, 15 कदम तक, हर कदम जाँचकर 3 बार तक दोबारा", "tool agent retry function calling टूल"),
        E("brain_live", "इस हिस्से का मॉडल — खुद चुनें", "DK लाइव के लिए कोई भी डली Gemini key और उसका मॉडल चुनें (नया मॉडल भी); खाली = fallback"),
        E("brain_chat", "इस हिस्से का मॉडल — खुद चुनें", "DK चैट और कहानी के लिए कोई भी डली key और उसका मॉडल चुनें; खाली = fallback"),
        E("brain_coder", "इस हिस्से का मॉडल — खुद चुनें", "कोडिंग एजेंट और वेबसाइट बनाने के लिए कोई भी डली key और उसका मॉडल चुनें; खाली = fallback"),
        E("brain_pc", "इस हिस्से का मॉडल — खुद चुनें", "DK कंप्यूटर के लिए कोई भी डली key और उसका मॉडल चुनें; खाली = fallback"),
        E("brain_live", "गहरी सोच — Z.ai / NVIDIA, चाहें तो", "कठिन सवाल पर DK लाइव Z.ai (GLM) या NVIDIA की key से सोचकर बोलता है — आवाज़ Gemini Live की ही"),
        E("brain_live", "DK लाइव — Google Gemini", "Gemini Live बातचीत, Gemini नेचुरल आवाज़ और सुनना — सिर्फ़ इसी key से"),
        E("brain_live", "लिखना (\"आप:\" वाली लाइन) — अपनी अलग Gemini key", "आप जो बोलते हैं उसे लिखने वाला (gemini-3.5-transcribe-live) इसी key से चलेगा, ताकि बोलने वाले Live की free limit इसमें खर्च न हो"),
        E("brain_live", "खोज — Tavily, Exa, Gemini (बैकअप क्रम)", "DK लाइव और चैट की इंटरनेट खोज पहले Tavily से; न चले तो अपने-आप Exa; वह भी न चले तो Gemini + Google Search"),
        E("brain_live", "गहरी खोज (डीप रिसर्च) — Exa, Tavily, Linkup, Firecrawl", "डीप रिसर्च, गहरी खोज, Linkup key, Firecrawl key, कई वेबसाइटों से हिंदी रिपोर्ट"),
        E("brain_live", "सुनना (आवाज़ → text) — Groq, चाहें तो", "बोली अब फ़ोन पर IndicConformer लिखता है (key नहीं)। Groq key डालें तो DK लाइव की गहरी सोच में काम आती है"),
        E("brain_chat", "मेरी Gemini key paid है", "DKAI होम पर सिर्फ़ फ्री मॉडल - paid key हो तो Pro जैसे paid मॉडल भी दिखें"),
        E("brain_chat", "DK चैट की AI", "चैट, आम जवाब, बिना-Live आवाज़ मोड का जवाब, search, गहरी सोच और कहानी — सिर्फ़ इसी की key से"),
        E("brain_chat", "तुरंत सेटअप — कोई भी फ्री key डालें", "DK खुद पहचानेगा कि key किस कंपनी की है, उसके फ्री मॉडल जाँचकर सबसे तेज़ चुनेगा और बात शुरू कर देगा"),
        E("persona", "JARVIS मोड", "JARVIS जैसी आवाज़ और अंदाज़ — काम सारे वही जो DK करता है. बोलें: \"जार्विस मोड ऑन करो\" / \"डीके मोड ऑन करो\""),
        E("persona", "DK का स्वभाव", "दोस्ताना / नटखट शरारती / गर्लफ्रेंड / प्रोफ़ेशनल / शांत — मूड समझकर बात, बगल की बातचीत (सुनेगी, जवाब नहीं)", "persona natkhat naughty professional girlfriend mood side talk personality स्वभाव मूड"),
        E("voice", "बोलने वाला इंजन (DK Live)", "DK लाइव (Direct Gemini Live Voice, डिफ़ॉल्ट) • Gemini TTS (बैकअप) — एक समय में एक"),
        E("voice", "Notification बोलकर बताओ (DK लाइव बंद हो तब भी)", "DK लाइव जुड़ा न हो तब भी नया मैसेज / notification आते ही DK बोलकर बताए — शुरू में बंद", "notification नोटिफिकेशन सूचना बोलकर बताओ पढ़ो announce read aloud message मैसेज whatsapp live बंद"),
        E("voice", "सुनना (माइक)", "लगातार सुनना — बिना बीप की आवाज़"),
        E("voice", "वेक वर्ड: हे डीके / हेलो डीके (फ़ोन के अंदर)", "ऐप बंद या फ़ोन लॉक हो तब भी \"हे डीके\" (Hey DK) या \"हेलो डीके\" (Hello DK) बोलिए — तुरंत DK लाइव. वेक वर्ड से पहले कुछ भी इंटरनेट पर नहीं जाता"),
        E("voice", "WhatsApp संदेश", "\"राहुल को WhatsApp पर लिखो …\" कहने पर DK खुद भेजता है और भेजने के बाद ही \"भेज दिया\" बोलता है"),
        E("voice", "टेम्पलेट कमांड", "\"व्हाट्सएप ओपन करो ___ को ___ मैसेज भेज दो\" जैसे साँचे — बिना API key, बिना AI"),
        E("voice", "ऑटोनोमस मोड", "DK खुद तय करे कि काम कोडिंग का है या फ़ोन के ऐप का, और खुद करे — शुरू में बंद"),
        E("memory", "Memory (याददाश्त)", "क्या-क्या सेव है देखें, कोई एक चीज़ या सारी मेमोरी मिटाएँ — सब सिर्फ़ इसी फ़ोन में"),
        E("memory", "सच्चा दोस्त", "DK करीबी दोस्त की तरह अपनेपन से बात करे, पिछली बातें याद रखे"),
        E("orb", "झलक", "ओर्ब पर टैप = चेहरा बनने वाला एनीमेशन"),
        E("orb", "ओर्ब का आकार", "सब छोटे-छोटे 3D डॉट से बने हैं — जो पसंद हो चुनें"),
        E("orb", "किनारों की चमक", "DK सुन या बोल रहा हो तो स्क्रीन के किनारे चमकें"),
        E("orb", "DK का चेहरा", "\"अपना चेहरा दिखाओ\" बोलने पर डॉट निकलकर चेहरा बनता है — आवाज़ महिला हो तो महिला चेहरा, पुरुष हो तो पुरुष"),
        E("orb", "DK Island", "DK से बाहर हों तो ऊपर काली गोली - टैप पर हाल के 5 काम, कॉल, YouTube / गाना, सारे notification, चार्जिंग, रील गिनती, दो कॉल अलग बुलबुले में, आकार छोटा-बड़ा, ऊपर से दूरी, नॉच (कैमरे पर छोटा, गोल / चौकोर, सूचना पर पूरा चौड़ा) notch nose"),
        E("ui", "DKAI का orb", "Fluid • Aurora • Sunset • Arc Reactor • Solar • Crystal • Particle Swarm • पुराना DK orb • orb बदलें"),
        E("ui", "ORB का साइज़ और रंग", "orb size • साइज़ छोटा बड़ा • कैरेक्टर बड़ा • orb color • रंग • मल्टी कलर • इंद्रधनुष • अपना रंग"),
        E("ui", "होम कैरेक्टर", "होम का कैरेक्टर • अवतार • avatar • character • तितली ऑर्ब • DK वेव ऑर्ब • होम की तस्वीर बदलें"),
        E("ui", "झलक", "बदलाव यहीं तुरंत दिखता है"),
        E("ui", "डार्क / लाइट मोड", "dark light mode डार्क लाइट मोड थीम theme उजला गहरा सफ़ेद काला रंग night day"),
        E("ui", "तैरता ORB", "बोलते-सुनते समय दिखने वाला ORB — उंगली से कहीं भी खिसकाओ, आकार छोटा/बड़ा"),
        E("ui", "एनिमेशन", "ओर्ब के छल्ले, पृष्ठभूमि की रोशनी और मेनू का खुलना"),
        E("ui", "टाइपिंग एनीमेशन", "जवाब आने से पहले चैट के बुलबुले में क्या चले — जो पसंद हो चुनें"),
        E("ui", "दिखावट पहले जैसी करें", "सिर्फ़ रंग-रूप शुरुआती जैसा — बातचीत, यादें, key और आवाज़ की सेटिंग नहीं मिटतीं"),
        E("access", "फ़ोन कंट्रोल (Accessibility)", "एक बार ON करें — फिर ON ही रहेगा"),
        E("access", "बार-बार बंद न हो", "Android बैटरी बचाने के लिए DK को बंद कर देता है — यह रोकें"),
        E("access", "सुनना और कॉल", ""),
        E("access", "DK कॉलिंग", "बोलकर कॉल उठाना, रिंग कौन-सी और कितनी तेज़", "call कॉल रिंग ring ringtone रिंगटोन घंटी उठाओ answer आवाज़ volume तेज़ धीमी"),
        E("access", "गड़बड़ी रिपोर्ट", "पिछली बार DK किस वजह से बंद हुआ"),
        E("access", "क्रैश रिपोर्ट", "आपकी मर्ज़ी से — शुरू से बंद", "crash क्रैश report रिपोर्ट bug बग भेजें send"),
        // 15 नवंबर 2026: पुराने "DK AI Plus" पन्ने की सुविधाएँ अपनी सही जगह (SettingsExtra.kt)
        E("guard", "इमरजेंसी SOS", "\"DK, activate SOS\" - 10 सेकंड की गिनती, फिर 5 लोगों को लोकेशन SMS + WhatsApp और कॉल", "sos एसओएस emergency इमरजेंसी आपातकाल मदद help location लोकेशन"),
        E("access", "ड्राइविंग मोड", "गाड़ी चलाते समय आने वाली कॉल अपने-आप कटे और उसी नंबर पर आपका SMS जवाब जाए", "driving drive ड्राइविंग गाड़ी car कार call कॉल sms"),
        E("access", "ऑटो इवेंट ट्रिगर", "चार्जर, Wi-Fi या बैटरी सेवर बदले तो DK बोलकर बताए", "event इवेंट trigger ट्रिगर charger चार्जर wifi वाईफाई battery बैटरी boot"),
        E("coder", "Termux ऑटोमेशन", "DK, Termux (F-Droid) में कमांड / लोकल सर्वर चला सके - RUN_COMMAND इजाज़त से", "termux टर्मक्स terminal टर्मिनल command कमांड linux server"),
        E("computer", "इमेज बनाना (मुफ़्त API)", "DK सर्वर पर Cloudflare FLUX - चैट, DK लाइव, DK कंप्यूटर; कोई key नहीं, आज की बची गिनती", "image इमेज photo फोटो तस्वीर picture चित्र flux cloudflare फ्लक्स लिमिट"),
        E("social", "YouTube चैनल जाँच", "\"मेरे चैनल का हाल बताओ\" - subscribers, views, नए वीडियो, comments (सिर्फ़ पढ़ना)", "youtube यूट्यूब channel चैनल subscriber सब्सक्राइबर views"),
        // 15 नवंबर 2026: नई सुविधाएँ (मालिक की सूची) - हर card सर्च में
        E("guard", "सायरन कब बजे", "छूने / उठाने पर सायरन • हमेशा पहरा: लॉक स्क्रीन पर गलत PIN = तुरंत सायरन", "wrong pin गलत पिन always हमेशा pocket जेब chori चोरी motion हिलाना छूना touch sensor"),
        E("guard", "टच मोड का रूप", "नॉर्मल • गॉड मोड (सबसे सख़्त) • स्टेल्थ मोड (चुपचाप)", "god गॉड stealth स्टेल्थ silent चुपचाप mode मोड"),
        E("face", "चेहरा पहचान", "Boss का चेहरा सेट करें — DK और टच मोड आपको पहचानें", "face फ़ेस चेहरा boss बॉस register रजिस्टर anti-spoof स्पूफ फ़ोटो वीडियो lens लेंस scan स्कैन"),
        E("face", "DK वॉचमैन", "कौन आया, कितनी बार, क्या किया — रिपोर्ट सिर्फ़ Boss के चेहरे से", "watchman वॉचमैन वाचमैन चौकीदार कौन आया visitor आने जाने रिपोर्ट report"),
        E("face", "जान-पहचान वाले चेहरे", "परिवार / दोस्त — नाम के साथ चेहरा सेव करें", "family परिवार friend दोस्त कौन who name नाम रिश्ता relation मम्मी पापा भाई बहन"),
        E("persona", "FRIDAY / VENOM मोड", "असिस्टेंट का दूसरा रूप — FRIDAY (काम की बात) • VENOM (मज़ाकिया पुरुष आवाज़) • हमदर्द (दिल से जुड़ने वाली)", "friday फ्राइडे venom वेनम persona पर्सोना jarvis हमदर्द hamdard दिल की बात"),
        E("computer", "ईमेल (IMAP/SMTP)", "DK आपके ईमेल पढ़े, जवाब भेजे, स्पैम हटाए — App Password से", "email ईमेल mail मेल gmail जीमेल inbox इनबॉक्स imap smtp app password signature हस्ताक्षर"),
        E("autoreply", "WhatsApp ग्रुप रिपोर्ट", "रोज़ की रिपोर्ट का टेम्पलेट - बोलकर भरो, ग्रुप में भेजो", "group ग्रुप report रिपोर्ट template टेम्पलेट whatsapp व्हाट्सएप"),
        E("ui", "नीचे की 5-टैब पट्टी", "होम • स्कैन • माइक • चैट • मेमोरी", "tab bar bottom nav टैब पट्टी नीचे की पट्टी होम स्कैन माइक चैट मेमोरी"),
        E("guard", "टच मोड", "कोई और फ़ोन छुए / उठाए तो सायरन + \"यह फ़ोन आपका नहीं है\""),
        E("guard", "गुप्त शब्द और भरोसेमंद नंबर", "बोलकर सायरन बंद • चोरी पर SMS जगह के साथ"),
        E("guard", "मालिक का चेहरा", "पहली बार एक बार चेहरा सेट करें — अलार्म में कैमरा मालिक को पहचानकर सायरन बंद करे"),
        E("guard", "कितना संवेदनशील", "उठाने / छूने पर कब बजे — कम • मध्यम • ज़्यादा"),
        E("guard", "और सुरक्षा", "छूने वाले की फ़ोटो • मालिक का चेहरा • पावर मेनू रोकना • चार्जर • SIM"),
        E("guard", "घुसपैठिए का रिकॉर्ड", "छूने वाले की फ़ोटो और क्या-क्या हुआ"),
        E("guard", "DK Privacy (बगल से न दिखे)", "notification ताले में • सभी ऐप या एक-एक ऐप • ऐप खोलें तब दिखे"),
        E("guard", "सीक्रेट PIN और फिर से लॉक", "चेतावनी स्क्रीन पर DK सीक्रेट PIN से सायरन बंद • गलत PIN = तुरंत फिर लॉक • सायरन में फ़ोन खुला = फिर लॉक", "secret pin सीक्रेट पिन गुप्त पिन relock फिर लॉक wrong pin गलत पिन"),
        E("applock", "ऐप लॉक", "DK और कोई भी ऐप — PIN • पैटर्न • फ़िंगरप्रिंट / फ़ेस • DK फ़ेस स्कैन", "app lock ऐप लॉक एप लॉक ताला locker लॉकर"),
        E("applock", "DK सीक्रेट PIN और पैटर्न", "ताला खोलने का अपना PIN और पैटर्न — टच मोड का सायरन भी इसी से बंद", "pin पिन pattern पैटर्न secret सीक्रेट"),
        E("applock", "ताला खोलने का तरीका", "PIN • पैटर्न • फ़ोन का फ़िंगरप्रिंट / फ़ेस • DK फ़ेस स्कैन (होलोग्राम)", "fingerprint फ़िंगरप्रिंट फिंगरप्रिंट face फ़ेस फेस biometric"),
        E("applock", "किन ऐप पर ताला", "DK खुद • WhatsApp • गैलरी • कोई भी ऐप चुनें", "whatsapp व्हाट्सएप gallery गैलरी apps ऐप"),
        E("applock", "फिर से ताला कब", "ऐप छोड़ते ही • 10 सेकंड • 1 मिनट • स्क्रीन बंद होने पर", "relock grace समय"),
        E("phonelock", "आवाज़ से फ़ोन लॉक / अनलॉक", "\"हे DK फोन लॉक करो\" • \"हे मायरा फोन अनलॉक करो\" — बिना AI, सीधे फ़ोन पर"),
        E("phonelock", "क्या बोलें", "बुलाने के नाम (DK, मायरा, जारवी…) और लॉक / अनलॉक के वाक्य"),
        E("phonelock", "फ़ोन का PIN / पैटर्न / पासवर्ड", "एक बार डालें — फ़ोन के अंदर ताले (Keystore) में सेव, कहीं भेजा नहीं जाता"),
        E("perms", "स्क्रीन देखना", "DK आपकी स्क्रीन देखकर काम करे (स्क्रीनशॉट AI को जाता है)"),
        E("perms", "ज़रूरी परमिशन", ""),
        E("update", "अपडेट", ""),
        E("license", LICENSE_CARD, ""), E("license", LICENSE_LOGIN_CARD, ""), E("license", LICENSE_ADMIN_CARD, ""),
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
