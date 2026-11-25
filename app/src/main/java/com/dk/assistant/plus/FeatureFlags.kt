package com.dk.assistant.plus

import android.content.Context

/**
 * 15 नवंबर 2026 (मालिक: "DK AI मास्टर अपग्रेड"): हर नई सुविधा का एक ही रजिस्टर.
 *
 * नियम (मालिक के शब्द): हर नई सुविधा / background listener / auto-reply / trigger / भारी service **पहली बार install पर
 * बंद (false)**. जब तक मालिक Settings में (हर सुविधा की अपनी पुरानी जगह, SettingsExtra.kt) खुद चालू न करे, कुछ भी पीछे से नहीं चलता.
 * अकेला अपवाद: "Realistic Typing" (मालिक की spec में Default = ON) - यह सिर्फ़ टाइप करने का तरीका है, कोई service नहीं.
 *
 * कोई license key / paywall / trial / रोज़ की सीमा नहीं - हर flag सबके लिए खुला.
 * Pure हिस्सा ([ALL], [byKey], [defaultOf]) JVM test में ([FeatureFlagsTest]).
 */
object FeatureFlags {
    const val PREFS = "dk_plus_flags"

    /** Settings में कहाँ दिखे: मुख्य सेटिंग्स या एडवांस्ड सेटिंग्स का कौन-सा हिस्सा */
    enum class Section(val title: String, val advanced: Boolean) {
        MEMORY("याददाश्त और बातचीत", false),
        CALL_DRIVE("कॉल और ड्राइविंग", false),
        SECRET("सीक्रेट सेटिंग्स", false),
        SUB_AGENT("सब-एजेंट (कई API keys)", false),
        EMAIL("ईमेल", false),
        SOCIAL("सोशल मीडिया", false),
        BATTERY("बैटरी सेवर पैनल", false),
        CREATE("इमेज और YouTube (बोलकर)", false),
        APPEARANCE("दिखावट", true),
        BEHAVIOR("व्यवहार (Behavior)", true),
        VOICE_GUARDIAN("वॉइस गार्डियन", true),
        FACE("फ़ेस रिकग्निशन", true),
        SOS("इमरजेंसी SOS", true),
        TOUCH_GUARD("टच गार्ड (चोरी से बचाव)", true),
        SCREEN_LOCK("स्क्रीन लॉक / ऑटो-अनलॉक", true),
        WA_REPLY("WhatsApp ऑटो रिप्लाई", true),
        EVENTS("ऑटो इवेंट ट्रिगर", true),
        TOOLS("टूल्स (Termux)", true),
    }

    data class Flag(val key: String, val title: String, val sub: String, val section: Section, val default: Boolean = false)

    // ---- keys (बाकी code इन्हीं नामों से पढ़ता है)
    const val AUTO_SAVE_MEMORIES = "plus_auto_save_memories"
    const val INCOGNITO = "plus_incognito"
    const val PROACTIVE_SPEECH = "plus_proactive_speech"
    const val AUTO_WAKE_WORD = "plus_auto_wake_word"
    const val MSG_ALERT_READER = "plus_msg_alert_reader"
    const val INSTA_AD_SKIP = "plus_insta_ad_skip"
    const val CALL_ANNOUNCE = "plus_call_announce"
    const val KEEP_RINGTONE = "plus_keep_ringtone"
    const val DRIVING_MODE = "plus_driving_mode"
    const val ADULT_STYLE = "plus_adult_style"
    const val KEY_POOL_ALL = "plus_key_pool_all"
    const val EMAIL_ON = "plus_email_on"
    const val SOCIAL_AUTO_POST = "plus_social_auto_post"
    /** 16 नवंबर 2026 (मालिक): सोशल पोस्ट स्पेशलिस्ट - गैलरी से खुद चुनना, कैप्शन/हैशटैग, कहने पर गाना/टैग, YouTube Shorts */
    const val SOCIAL_SPECIALIST = "plus_social_specialist"
    const val BATTERY_SAVER = "plus_battery_saver"
    const val BS_WAKE_WORD = "plus_bs_wake_word"
    const val BS_SCREEN_AWARE = "plus_bs_screen_aware"
    const val BS_VOICE_GUARDIAN = "plus_bs_voice_guardian"
    /** 15 नवंबर 2026 (मालिक: "अलग 5-टैब वाली नीचे की पट्टी भी बनाओ"): पट्टी दिखती है; यह स्विच सिर्फ़ छुपाने का (बंद = दिखे) */
    const val HIDE_HOME_TABS = "plus_hide_home_tabs"
    const val FLOAT_ORB = "plus_float_orb"
    const val EDGE_GLOW = "plus_edge_glow"
    const val EARPIECE = "plus_earpiece"
    const val ECHO_GUARD = "plus_echo_guard"
    const val MEDIA_MODE = "plus_media_mode"
    const val START_ON_BOOT = "plus_start_on_boot"
    const val REALISTIC_TYPING = "plus_realistic_typing"
    const val VOICE_GUARDIAN = "plus_voice_guardian"
    const val VG_OWNER_ONLY = "plus_vg_owner_only"
    const val VG_AWAY = "plus_vg_away"
    /** 16 नवंबर 2026 (मालिक): 15 सैंपल वाली पहचान - हर सैंपल अलग याद, मिलान सबसे मिलते सैंपल से (बंद = पुराना औसत) */
    const val VG_DEEP = "plus_vg_deep"
    /** 16 नवंबर 2026 (मालिक): पक्की रोक - पहचान होने तक आवाज़ DK तक नहीं; अनजान आवाज़ यहीं कट (बंद = पहले जैसा, सिर्फ़ DK व्यस्त हो तब रुकती) */
    const val VG_STRICT = "plus_vg_strict"
    /** 21 नवंबर 2026 (मालिक): अवेयरनेस मोड - आवाज़ से कौन बोल रहा (बॉस / परिवार / अनजान), DK उसी हिसाब से ([com.dk.assistant.voice.Awareness]) */
    const val VG_AWARE = "plus_vg_aware"
    const val FACE_ON = "plus_face_on"
    const val FACE_APP_LOCK = "plus_face_app_lock"
    const val FACE_VAULT = "plus_face_vault"
    const val FACE_WATCHMAN = "plus_face_watchman"
    const val FACE_MORNING = "plus_face_morning"
    const val SOS_ON = "plus_sos_on"
    /** 15 नवंबर 2026 (हिस्सा 4): कोई उठा ले तब भी सबको पूरी 5 बार कॉल (बंद = उठाते ही रुको, पहले जैसा) */
    const val SOS_CALL_ALL = "plus_sos_call_all"
    const val TOUCH_GUARD = "plus_touch_guard"
    const val TG_CHARGER = "plus_tg_charger"
    const val SCREEN_UNLOCK = "plus_screen_unlock"
    const val WA_AUTO_REPLY = "plus_wa_auto_reply"
    const val WA_GROUPS = "plus_wa_groups"
    const val EVENTS_ON = "plus_events_on"
    const val EV_CHARGER_ON = "plus_ev_charger_on"
    const val EV_CHARGER_OFF = "plus_ev_charger_off"
    const val EV_WIFI_ON = "plus_ev_wifi_on"
    const val EV_WIFI_OFF = "plus_ev_wifi_off"
    const val EV_BATTERY_SAVER = "plus_ev_battery_saver"
    const val TERMUX = "plus_termux"
    const val IMAGE_GEN = "plus_image_gen"
    const val YT_CHANNEL = "plus_yt_channel"
    // 15 नवंबर 2026 (मालिक की सूची): WhatsApp ग्रुप रिपोर्ट (3D कैरेक्टर बाद में मालिक के कहने पर हटाया)
    const val WA_GROUP_REPORT = "plus_wa_group_report"
    /** 15 नवंबर 2026 (मालिक, हिस्सा 1): AI दिमाग → टूल एजेंट (Gemini function calling + Retry Loop) */
    const val TOOL_AGENT = "plus_tool_agent"
    /** 24 नवंबर 2026 (मालिक: DK CONTENT WRITING): Story Writer + फैक्ट्स / स्क्रिप्ट / कैप्शन एक ही जगह (शुरू में बंद) */
    const val CONTENT_WRITING = "plus_content_writing"
    /** 24 नवंबर 2026 (मालिक: Universal AI Action Agent): केंद्रीय DK मास्टर - प्लान दिखाना, जोखिम पर रुकना, हर काम का लॉग */
    const val MASTER_AGENT = "plus_master_agent"

    val ALL: List<Flag> = listOf(
        Flag(AUTO_SAVE_MEMORIES, "बातों से याददाश्त अपने-आप सेव", "बातचीत में मिली नई बात DK खुद याद रखे", Section.MEMORY),
        Flag(INCOGNITO, "इनकॉग्निटो मोड", "इस दौरान DK न कुछ याद रखे, न पुरानी याददाश्त पढ़े", Section.MEMORY),
        Flag(PROACTIVE_SPEECH, "खुद से बोलना (Proactive)", "बंद = DK सिर्फ़ तब बोले जब आप बुलाएँ", Section.MEMORY),
        Flag(AUTO_WAKE_WORD, "\"Hey DK\" अपने-आप शुरू", "ऐप खुलते ही जागने वाला शब्द सुनना शुरू", Section.MEMORY),
        Flag(MSG_ALERT_READER, "मैसेज पढ़कर सुनाना", "WhatsApp के नए मैसेज बोलकर बताए", Section.MEMORY),
        Flag(INSTA_AD_SKIP, "Instagram ऑटो-स्क्रॉल और ऐड-स्किप", "Sponsored पोस्ट/ऐड अपने-आप आगे (Accessibility)", Section.MEMORY),
        Flag(CALL_ANNOUNCE, "कॉल की घोषणा", "कॉल करने वाले का नाम बोले, \"Receive\" / \"Reject\" सुने", Section.CALL_DRIVE),
        Flag(KEEP_RINGTONE, "रिंगटोन भी बजती रहे", "घोषणा के साथ रिंगटोन बंद न हो", Section.CALL_DRIVE),
        Flag(DRIVING_MODE, "ड्राइविंग मोड", "कॉल अपने-आप काटे और SMS से जवाब भेजे ({user_name})", Section.CALL_DRIVE),
        Flag(ADULT_STYLE, "18+ बातचीत शैली", "उम्र की पुष्टि के बाद ही; सिर्फ़ बोलचाल खुली, अश्लील सामग्री नहीं", Section.SECRET),
        Flag(KEY_POOL_ALL, "सारी keys इस्तेमाल करो (Round-Robin)", "कोडिंग एजेंट की Gemini key 1-5 और DK लाइव की key 1-3: हर नया काम / नई बातचीत अगली key से, एक key की limit (429) पर अगली key - काम नहीं रुकता", Section.SUB_AGENT),
        Flag(EMAIL_ON, "ईमेल पढ़ना / जवाब देना", "IMAP/SMTP App Password से", Section.EMAIL),
        Flag(SOCIAL_AUTO_POST, "रोज़ की ऑटो-स्टोरी / ऑटो-पोस्ट", "तय समय पर पोस्ट तैयार करके डालना", Section.SOCIAL),
        Flag(SOCIAL_SPECIALIST, "सोशल पोस्ट स्पेशलिस्ट", "गैलरी से खुद चुने, देखकर कैप्शन/हैशटैग, कहने पर गाना/टैग, YouTube Shorts - आख़िर में \"हाँ\" पूछकर", Section.SOCIAL),
        Flag(BATTERY_SAVER, "बैटरी सेवर (मुख्य स्विच)", "चालू = नीचे चुने भारी हिस्से रुकें", Section.BATTERY),
        Flag(BS_WAKE_WORD, "Wake Word रोको", "बैटरी सेवर में \"Hey DK\" सुनना बंद", Section.BATTERY),
        Flag(BS_SCREEN_AWARE, "स्क्रीन देखना रोको", "बैटरी सेवर में स्क्रीन पढ़ना बंद", Section.BATTERY),
        Flag(BS_VOICE_GUARDIAN, "वॉइस गार्डियन रोको", "बैटरी सेवर में आवाज़-पहचान बंद", Section.BATTERY),
        Flag(HIDE_HOME_TABS, "नीचे की 5-टैब पट्टी छुपाएँ", "होम • स्कैन • माइक • चैट • मेमोरी (बंद = पट्टी दिखे)", Section.APPEARANCE),
        Flag(FLOAT_ORB, "फ़्लोटिंग ओर्ब", "हर ऐप के ऊपर छोटा DK ओर्ब", Section.BEHAVIOR),
        Flag(EDGE_GLOW, "किनारे की चमक (Edge Glow)", "DK बोले/सुने तो स्क्रीन के किनारे चमकें", Section.BEHAVIOR),
        Flag(EARPIECE, "ईयरपीस मोड", "DK की आवाज़ ऊपर वाले कॉल स्पीकर में (निजी बातचीत)", Section.BEHAVIOR),
        Flag(ECHO_GUARD, "इको गार्ड", "DK अपनी ही स्पीकर की आवाज़ सुनकर जवाब न दे", Section.BEHAVIOR),
        Flag(MEDIA_MODE, "मीडिया मोड", "YouTube/गाने के साथ आवाज़ संतुलित, स्क्रीन रिकॉर्डर में भी DK की आवाज़", Section.BEHAVIOR),
        Flag(START_ON_BOOT, "फ़ोन चालू होते ही DK शुरू", "Boot पर", Section.BEHAVIOR),
        Flag(REALISTIC_TYPING, "असली जैसी टाइपिंग", "लंबा पैराग्राफ़ एक बार में, छोटा अक्षर-अक्षर", Section.BEHAVIOR, default = true),
        Flag(VOICE_GUARDIAN, "वॉइस गार्डियन (आवाज़-पहचान)", "RedimNet मॉडल इंटरनेट से डाउनलोड होता है", Section.VOICE_GUARDIAN),
        Flag(VG_OWNER_ONLY, "सिर्फ़ Boss की आवाज़", "अनजान आवाज़ को अनसुना करो", Section.VOICE_GUARDIAN),
        Flag(VG_AWAY, "Away मोड", "अनजान आवाज़: पहली बार चेतावनी, दूसरी बार फ़ोन लॉक", Section.VOICE_GUARDIAN),
        Flag(VG_DEEP, "15 सैंपल वाली पक्की पहचान", "हर सैंपल अलग याद, मिलान सबसे मिलते सैंपल से", Section.VOICE_GUARDIAN),
        Flag(VG_STRICT, "पक्की रोक (अनजान आवाज़ DK तक बिल्कुल नहीं)", "पहचान होने तक आवाज़ रुकी (~0.6 सेकंड), फिर Boss की आवाज़ तुरंत DK को", Section.VOICE_GUARDIAN),
        Flag(VG_AWARE, "अवेयरनेस मोड", "आवाज़ से पहचान: बॉस / परिवार (मम्मी, पापा, भैया...) / अनजान - DK उसी हिसाब से बात करे", Section.VOICE_GUARDIAN),
        Flag(FACE_ON, "फ़ेस रिकग्निशन", "10 MB मॉडल इंटरनेट से डाउनलोड होता है", Section.FACE),
        Flag(FACE_APP_LOCK, "चेहरे से ऐप/सेक्शन लॉक", "Settings / Chat / Memories / चुने ऐप", Section.FACE),
        Flag(FACE_VAULT, "प्राइवेट वॉल्ट", "DK की ली फ़ोटो गैलरी में नहीं, ऐप के ताले में", Section.FACE),
        Flag(FACE_WATCHMAN, "वॉचमैन मोड", "कौन आया, कितनी बार - Boss को रिपोर्ट", Section.FACE),
        Flag(FACE_MORNING, "सुबह का स्वागत", "दिन के पहले अनलॉक पर चेहरा देखकर नमस्ते", Section.FACE),
        Flag(SOS_ON, "इमरजेंसी SOS", "\"DK, activate SOS\" - 5 लोगों को लोकेशन + कॉल", Section.SOS),
        Flag(SOS_CALL_ALL, "सबको पूरी 5 बार कॉल", "कोई उठा ले तब भी बाकी नंबरों पर कॉल जारी", Section.SOS),
        Flag(TOUCH_GUARD, "टच गार्ड", "\"DK, turn on Touch Guard\" - 12 सेकंड बाद चालू", Section.TOUCH_GUARD),
        Flag(TG_CHARGER, "चार्जर निकालने पर अलार्म", "टच गार्ड चालू हो तब", Section.TOUCH_GUARD),
        Flag(SCREEN_UNLOCK, "स्क्रीन ऑटो-अनलॉक", "PIN/पैटर्न Android Keystore में - तय काम के लिए अनलॉक, फिर लॉक", Section.SCREEN_LOCK),
        Flag(WA_AUTO_REPLY, "WhatsApp ऑटो रिप्लाई", "Notification से जवाब (DK सोया हो तब भी)", Section.WA_REPLY),
        Flag(WA_GROUPS, "ग्रुप में भी जवाब", "बंद = सिर्फ़ निजी चैट", Section.WA_REPLY),
        Flag(WA_GROUP_REPORT, "WhatsApp ग्रुप रिपोर्ट", "सेव टेम्पलेट में बोलकर भरो, सुनकर हाँ पर ग्रुप में भेजो", Section.WA_REPLY),
        Flag(EVENTS_ON, "ऑटो इवेंट ट्रिगर (मुख्य स्विच)", "नीचे चुनी घटनाओं पर DK बोलकर बताए", Section.EVENTS),
        Flag(EV_CHARGER_ON, "चार्जर लगा", "", Section.EVENTS),
        Flag(EV_CHARGER_OFF, "चार्जर हटा", "", Section.EVENTS),
        Flag(EV_WIFI_ON, "Wi-Fi जुड़ा", "", Section.EVENTS),
        Flag(EV_WIFI_OFF, "Wi-Fi टूटा", "", Section.EVENTS),
        Flag(EV_BATTERY_SAVER, "बैटरी सेवर बदला", "", Section.EVENTS),
        Flag(TERMUX, "Termux ऑटोमेशन", "Termux में कमांड / लोकल सर्वर (RUN_COMMAND इजाज़त)", Section.TOOLS),
        Flag(IMAGE_GEN, "बोलकर तस्वीर बनाना", "\"एक शेर की तस्वीर बनाओ\" - DK सर्वर का Cloudflare FLUX (चैट, DK लाइव, DK कंप्यूटर)", Section.CREATE),
        Flag(TOOL_AGENT, "टूल एजेंट (Retry Loop)", "लंबे फ़ोन-काम: Gemini खुद tool चुने (15 कदम तक), हर कदम जाँचकर 3 बार तक दोबारा", Section.BEHAVIOR),
        Flag(CONTENT_WRITING, "DK कंटेंट राइटिंग", "कहानी (पहले जैसी) + फैक्ट्स / स्क्रिप्ट / कैप्शन / सुधार / फ़ोटो से - एक ही जगह", Section.CREATE),
        Flag(MASTER_AGENT, "DK मास्टर एजेंट", "बड़े काम पर पहले प्लान दिखाए, पैसे / पासवर्ड / OTP पर रुककर पूछे, हर काम का लॉग", Section.BEHAVIOR),
        Flag(YT_CHANNEL, "बोलकर YouTube चैनल जाँच", "\"मेरे चैनल का हाल बताओ\" - subscribers, views, नए वीडियो, comments", Section.CREATE),
    )

    /**
     * जो flag सच में काम से जुड़े हैं - सिर्फ़ इन्हीं के स्विच Settings में दिखते हैं (अपनी पुरानी जगह, SettingsExtra.kt).
     * 15 नवंबर 2026 (मालिक): बाकी flag Settings में बिल्कुल नहीं दिखते - न "अगला चरण" वाला बंद स्विच, न दोहराव
     * (जो सुविधा पहले से बनी है - ओर्ब, टच मोड, फ़ोन लॉक, ऑटो रिप्लाई, आवाज़ - वह अपनी पुरानी सेटिंग से ही चलती है).
     */
    val READY: Set<String> = setOf(
        INCOGNITO, DRIVING_MODE, SOS_ON, EVENTS_ON, EV_CHARGER_ON, EV_CHARGER_OFF, EV_WIFI_ON, EV_WIFI_OFF,
        EV_BATTERY_SAVER, TERMUX, START_ON_BOOT, FACE_VAULT, IMAGE_GEN, YT_CHANNEL,
        // 15 नवंबर 2026: चेहरा पहचान (DK लाइव "देखो कौन है"), ईमेल, ग्रुप रिपोर्ट
        FACE_ON, EMAIL_ON, WA_GROUP_REPORT,
        // 15 नवंबर 2026: DK वॉचमैन
        FACE_WATCHMAN,
        // 15 नवंबर 2026: टूल एजेंट (AI दिमाग)
        TOOL_AGENT,
        // 24 नवंबर 2026: DK मास्टर एजेंट (केंद्रीय दिमाग)
        MASTER_AGENT,
        // 15 नवंबर 2026: वॉइस गार्डियन (हिस्सा 2)
        VOICE_GUARDIAN, VG_OWNER_ONLY, VG_AWAY,
        // 16 नवंबर 2026: वॉइस गार्डियन - 15 सैंपल
        VG_DEEP, VG_STRICT,
        // 15 नवंबर 2026: SOS - सबको पूरी 5 बार (हिस्सा 4)
        SOS_CALL_ALL,
        // 16 नवंबर 2026: सोशल पोस्ट स्पेशलिस्ट
        SOCIAL_SPECIALIST,
        // 16 नवंबर 2026: सारी keys इस्तेमाल करो (कोडिंग एजेंट की Gemini key 1-5, Round-Robin)
        KEY_POOL_ALL,
        // 24 नवंबर 2026: DK कंटेंट राइटिंग
        CONTENT_WRITING,
    )
    fun ready(key: String): Boolean = key in READY

    private val INDEX = ALL.associateBy { it.key }

    fun byKey(key: String): Flag? = INDEX[key]
    fun defaultOf(key: String): Boolean = INDEX[key]?.default ?: false
    fun inSection(s: Section): List<Flag> = ALL.filter { it.section == s }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(c: Context, key: String): Boolean = try { sp(c).getBoolean(key, defaultOf(key)) } catch (_: Exception) { defaultOf(key) }

    fun set(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
        listeners.forEach { try { it(key, value) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
    }

    /** बैटरी सेवर चालू हो और यह भारी हिस्सा उसमें रुका हो = true (हिस्सा अभी न चले) */
    fun suspendedByBattery(c: Context, bsKey: String): Boolean = on(c, BATTERY_SAVER) && on(c, bsKey)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    fun listen(l: (String, Boolean) -> Unit) { listeners.add(l) }

    /** छोटे text/number मान (SOS नंबर, ड्राइविंग संदेश ...) - इसी फ़ाइल में */
    fun str(c: Context, key: String, def: String = ""): String = try { sp(c).getString(key, def) ?: def } catch (_: Exception) { def }
    fun putStr(c: Context, key: String, v: String) { sp(c).edit().putString(key, v).apply() }

}
