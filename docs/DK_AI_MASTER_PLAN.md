# DK AI मास्टर अपग्रेड - योजना और ऑडिट (15 नवंबर 2026)

मालिक की spec: हर सुविधा मुफ़्त और खुली, कोई license / trial / paywall / Hunter TTS नहीं, हर नई सुविधा शुरू में **बंद**
(अपवाद: Realistic Typing), भारी मॉडल APK में नहीं - ज़रूरत पर इंटरनेट से (`filesDir/models/`).
काम चरणों (Phase) में, हर चरण एक अलग PR. पुरानी सुविधाएँ हटाई या दोहराई नहीं जातीं - उन्हीं को सुधारा जाता है.

## Phase 1 (यह PR) - नींव

| हिस्सा | फ़ाइल | हालत |
|---|---|---|
| License / access key पूरी तरह हटाया | `license/` हटाया, MainActivity/DKAccessibilityService/AgentRunner/Settings से जाँच हटाई | पुराना code `backup/dk-master-upgrade-1115` में |
| सभी नई सुविधाओं का रजिस्टर (सब बंद) | `plus/FeatureFlags.kt` | बना |
| ModelManager (WorkManager + OkHttp Range-resume + SHA-256 + प्रगति) | `plus/models/ModelManager.kt`, `ModelCatalog.kt` | बना - RedimNet B2/B3, SFace, YuNet, 4 मुफ़्त 3D कैरेक्टर (.glb) सिर्फ़ URL+SHA |
| Verify-and-retry UI चक्र (node ID → text → tap → scroll) | `plus/RetryLoop.kt`, `plus/A11yRetry.kt` | बना |
| Sub-agent key pool (round-robin, 429 पर अगली key) | `plus/KeyPool.kt` | बना (Settings में keys), AI कॉल से जोड़ना Phase 2 |
| इमरजेंसी SOS (5 नंबर, SMS + WhatsApp + 5 बार कॉल) | `plus/Sos.kt` + "DK, activate SOS" | बना |
| ड्राइविंग मोड (कॉल काटे + {user_name} SMS) | `plus/DrivingMode.kt` + CallManager hook | बना |
| ऑटो इवेंट ट्रिगर (चार्जर, Wi-Fi, बैटरी सेवर) | `plus/EventTriggers.kt`, `plus/PlusBoot.kt` | बना |
| Termux RUN_COMMAND (कमांड, लोकल सर्वर, Chrome में खोलना) | `plus/Termux.kt` | बना |
| इमेज बनाना (मुफ़्त Pollinations / अपना OpenAI-compatible) | `plus/ImageGen.kt` | बना |
| इनकॉग्निटो (याद न रखे, न पढ़े) | `Memory.addFact` / `factsBlock` | बना |
| ~~Settings → "DK AI Plus"~~ | ~~`plus/PlusSettingsActivity.kt`~~ | **15 नवंबर 2026 को हटाया** - सुविधाएँ अपनी पुरानी जगह (नीचे PR B) |

## पहले से मौजूद (सुधारना है, दोबारा नहीं बनाना)

फ़ेस (YuNet/SFace डाउनलोड), मालिक की आवाज़ (CAM++), टच मोड (Guard), फ़ोन लॉक/अनलॉक, ऑटो रिप्लाई, फ़्लोटिंग ओर्ब,
Edge Glow, JARVIS मोड, DK स्वभाव (Persona), भाषा (hinglish/hi/en/auto), बैकअप (Google Drive), Whiteboard/Teacher,
PDF (ComputerOffice), Live Vision, परमिशन, कोडिंग एजेंट, सोशल रिव्यू.

## आगे के चरण

- **Phase 2**: ~~5-tab bottom nav (Home/Scan/Mic/Memories/Chat)~~ (PR D में बना) + side drawer; Friday/Venom personas; 20+ भाषाएँ;
  Swift/Soul/Mind voice modes; KeyPool को सारे AI कॉल से जोड़ना; Battery Saver panel असली रोक; call announce.
- **Phase 3**: Voice Guardian (RedimNet, 6-step enrollment, Away mode) + Face (app lock, vault, watchman, morning greeting);
  God/Stealth touch guard; 3D कैरेक्टर (WebView + three.js, GLTFLoader साथ में डाउनलोड).
- **Phase 4**: Market (~32 tools), Marketing Suite, Documents (XLSX/HTML), Study Whiteboard upgrade, Coding workspace.
- **Phase 5**: Email (IMAP/SMTP), WhatsApp Group Report, Social auto-post, Deepgram / OpenRouter audio, wallpaper.

18+ शैली: सिर्फ़ उम्र-पुष्टि के बाद बोलचाल का अंदाज़; अश्लील सामग्री कभी नहीं बनेगी.

## PR A (15 नवंबर 2026) - मुफ़्त API चरण

| सुविधा | फ़ाइल | हाल |
|---|---|---|
| Smart Home (DK Home) पूरी तरह हटाया | - (`NoSmartHomeTest`) | हटा |
| मुफ़्त इमेज: Pollinations / AI Horde / Hugging Face / अपनी API - हर एक की अलग key | `plus/ImageGen.kt` | बना |
| YouTube चैनल जाँच (बिना key RSS, key से Data API v3) | `plus/YtChannel.kt` | बना |
| Plug-in tools (नया फीचर = एक class + एक लाइन) | `plus/tools/PlusTools.kt` | बना |
| Dynamic Tool Router: 45+ routed tools पर सिर्फ़ नाम + `dk_tool_search` | `LiveToolSlim.kt` | बना |
| भाषा: भोजपुरी + मराठी; Auto हर भाषा | `Lang.kt` | बना |

नया Live tool जोड़ना: `PlusTool` लागू करो (name, flag, declaration, run), `FeatureFlags` में बंद flag + READY, `PlusTools.ALL` में जोड़ो।

## PR B (15 नवंबर 2026) - "DK AI Plus" पन्ना हटाकर सुविधाएँ पुरानी सेटिंग में

मालिक: "DK AI Plus नाम हटाओ; जो सेटिंग पहले से बनी है उसी के अंदर डालो; जो बना है उसे दोबारा मत बनाओ, सुधारो;
झूठे स्विच नहीं; डाउनलोड लिखा है तो सच में डाउनलोड हो; हर key DK के अंदर ही डले।"
पुराना पन्ना `backup/dk-plus-settings-1115` में सुरक्षित।

| सुविधा | नई जगह (पहले से बनी सेटिंग) | फ़ाइल |
|---|---|---|
| इमरजेंसी SOS (स्विच, 5 नंबर, रोकें) | सुरक्षा → टच मोड | `SettingsExtra.kt` `secSos()` |
| ड्राइविंग मोड (स्विच, SMS संदेश) | ऑटोमेशन → Accessibility | `secDriving()` |
| ऑटो इवेंट ट्रिगर (+ फ़ोन चालू होने पर फिर शुरू) | ऑटोमेशन → Accessibility | `secEvents()` |
| Termux ऑटोमेशन (इजाज़त, जाँच कमांड) | दिमाग और आवाज़ → कोडिंग एजेंट | `secTermux()` |
| इमेज बनाना (हर मुफ़्त API की key, प्राइवेट वॉल्ट, जाँच) | दिमाग और आवाज़ → DK कंप्यूटर | `secImageGen()` |
| YouTube चैनल जाँच (handle, key, अभी जाँचें) | सोशल और कहानी → सोशल रिव्यू | `secYouTube()` |
| इनकॉग्निटो | आवाज़ → Memory (याददाश्त) | `SettingsVoice.kt` |

UI से हटाया (कोड/flag वैसे ही, ताकि पुराने pref न टूटें):
- ~36 "(अगला चरण)" वाले बंद स्विच - इनमें से ज़्यादातर सुविधाएँ पहले से अपनी जगह बनी हैं (तैरता ORB, किनारों की चमक,
  टच मोड, फ़ोन लॉक/अनलॉक, ऑटो रिप्लाई, Hey DK, कॉल, JARVIS/स्वभाव, Memory, बैकअप)।
- "मॉडल डाउनलोड" कार्ड - वहाँ के मॉडल किसी सुविधा से जुड़े नहीं थे (डाउनलोड होकर बेकार पड़े रहते)। चेहरा (YuNet/SFace)
  और मालिक की आवाज़ (CAM++) के असली डाउनलोड पहले से टच मोड / Hey DK में हैं - अब वहाँ प्रगति MB में भी (`DlText`, जैसे "55% (15.5/28.3 MB)")।
- सब-एजेंट key पूल - keys सेव होती थीं पर कोई AI कॉल उन्हें नहीं पढ़ता था। हर हिस्से की बैकअप keys (DK लाइव 3 keys, कोडिंग एजेंट
  सारी keys पर fallback) पहले से AI दिमाग में हैं।

### DK लाइव हर सुविधा जानता है और चालू-बंद / चला सकता है
- वही पुराना `dk_settings` tool बढ़ाया (नया नहीं बनाया): SOS, ड्राइविंग मोड, इवेंट ट्रिगर (+ चार्जर/Wi-Fi/बैटरी सेवर), फ़ोन चालू होते ही ट्रिगर, Termux, इमेज बनाना, प्राइवेट वॉल्ट, YouTube चैनल जाँच, इनकॉग्निटो, ऑटो रिप्लाई, ऑटोनोमस मोड.
- वही दो-बार-पक्का नियम ("ठीक है बॉस" + "सर, क्या आप सच में ...?").
- सुविधा बंद हो और यूज़र उसे माँगे → DK बताता है और चालू करने की पेशकश करता है; फ़ोन की इजाज़त बाकी हो तो सेटिंग खोलने को कहता है.
- चलाना: `free_image`, `youtube_channel`, नया `termux_run` (डेटा मिटाने / reboot / su / curl|sh वाली कमांड बोलकर नहीं चलती).

### मास्टर प्रॉम्प्ट बनाम ऐप (ऑडिट)

| प्रॉम्प्ट की चीज़ | हाल |
|---|---|
| मुफ़्त API (Gemini, Groq, OpenRouter, NVIDIA, custom), कोई license/paywall/Hunter TTS नहीं | पहले से (`NoPaywallTest`) |
| Smart Home नहीं | हटा (`NoSmartHomeTest`) |
| Personas DK / Jarvis + स्वभाव (दोस्ताना, शरारती, गर्लफ्रेंड, प्रोफ़ेशनल, शांत) | पहले से; Friday / Venom - PR C (15 नवंबर 2026) |
| भाषाएँ | Hinglish, हिंदी, English, भोजपुरी, मराठी + Auto (हर भाषा) - मालिक का पिछला फ़ैसला: "बाकी Auto खुद देख लेगा" |
| Memory, इनकॉग्निटो, बैकअप/रिस्टोर (Drive), अपडेट | पहले से |
| टच मोड, फ़ोन लॉक/अनलॉक, ऑटो रिप्लाई, SOS, ड्राइविंग, इवेंट ट्रिगर, Termux, इमेज, YouTube | पहले से (ऊपर वाली जगहों पर) |
| Dynamic tool router (`dk_tool_search`), retry loop | पहले से (`LiveToolSlim`, `plus/RetryLoop.kt`) |
| चेहरा पहचान वाली सेटिंग / "सिर्फ़ मेरी आवाज़ सुनो" | मालिक ने पहले खुद हटवाई (v3.99, v4.5) - दोबारा नहीं जोड़ी |
| Email (IMAP/SMTP), WhatsApp ग्रुप रिपोर्ट, 3D कैरेक्टर, God/Stealth मोड, Friday/Venom, DuckDuckGo खोज, चेहरा पहचान | PR C (15 नवंबर 2026) में बना |
| Market, Marketing Suite | मालिक ने मना किया - नहीं बनाना |
| 5-tab होम | होम में पहले से कैमरा/माइक/चैट/मेमोरी; मेनू में "स्कैन" + "3D कैरेक्टर" जोड़े. अलग tab bar नहीं (मालिक चाहें तो अलग PR) |


## PR C (15 नवंबर 2026) - बाकी सारी सुविधाएँ (Market / Marketing Suite छोड़कर)

मालिक: "मार्केट और मार्केटिंग सूट मत बनाओ, बाकी सब बनाओ… DuckDuckGo का फ्री API न हो तो मत बनाओ… DK को सब पता होना चाहिए…
सेटिंग में चेहरा पहचान / फ़ेस रजिस्ट्रेशन चाहिए… जो नाम सेट हो वही 'जी बॉस' / 'हेलो बॉस'… भारी चीज़ इंटरनेट से डाउनलोड।"
v3.99 वाला "चेहरा पहचान हटाओ" फ़ैसला मालिक ने खुद पलटा; पुराना हाल `backup/dk-face-settings-1115` में।

| सुविधा | कहाँ | फ़ाइल | शुरू में |
|---|---|---|---|
| नाम से जवाब ("जी बॉस", "हेलो बॉस") | ऑनबोर्डिंग / सिस्टम → प्रोफ़ाइल | `NameRule.kt`, `WakeMatch.acksFor` | हमेशा (नाम सेट हो तो) |
| FRIDAY / VENOM | आवाज़ → FRIDAY / VENOM मोड | `AssistantPersona.kt` | बंद |
| DuckDuckGo (मुफ़्त Instant Answer, बिना key) | खुला वेब खोज के साथ | `OpenSearch.kt` | खोज के साथ |
| चेहरा पहचान + जान-पहचान वाले चेहरे | सुरक्षा → चेहरा पहचान | `FacePeople.kt`, `SettingsFace.kt`, `FaceWhoActivity.kt` | बंद, मॉडल डाउनलोड |
| God / Stealth | सुरक्षा → टच मोड → टच मोड का रूप | `GuardModes.kt` | सामान्य |
| ईमेल (IMAP/SMTP, app password) | दिमाग और आवाज़ → DK कंप्यूटर | `plus/Mail.kt` | बंद |
| WhatsApp ग्रुप रिपोर्ट | ऑटोमेशन → ऑटो रिप्लाई | `plus/GroupReport.kt` | बंद |
| 3D कैरेक्टर (GLB + three.js loader डाउनलोड, SHA-256) | रूप → 3D कैरेक्टर, होम मेनू | `plus/Character3D.kt`, `Character3DActivity.kt` | बंद |

DK लाइव: हर नई चीज़ `dk_settings` से चालू/बंद (बदलने पर दो बार पक्का), और tools `face_who`, `email_read`, `email_send`,
`email_delete`, `group_report`, `show_character` से चलती है. भेजना / हटाना = पहले सुनाकर "हाँ" पर ही.

## PR D (15 नवंबर 2026) - सच्चा DK + नीचे की 5-टैब पट्टी

मालिक: "जो काम नहीं हुआ, फिर भी बोलता है हाँ हो गया; कोई कॉल नहीं आ रहा, फिर भी बोलता है कॉल आ रहा है। दोबारा चेक करे, हुआ हो तभी हाँ।"

जड़ और सुधार:
- घंटी बंद (कटी / छूटी / उठ गई) होने की ख़बर DK लाइव को कभी नहीं जाती थी, इसलिए उसकी याद में "कॉल आ रहा है" बना रहता था।
  अब घंटी बंद होते ही Live को चुपचाप बताया जाता है (`GeminiLiveTruth.noteCallGone`, `turnComplete=false`, इस पर जवाब नहीं बनता)।
- फिर भी Live "कॉल आ रहा है" बोले और फ़ोन पर न घंटी हो, न चलती कॉल, तो वह वाक्य वहीं रुकता है और Live सच बोलता है (`falseCallCheck`)।
- ऐप का "Missed call / छूटी हुई कॉल" notification अब बजती कॉल नहीं माना जाता। 60s से पुराना "बजती" notification भी नहीं (`AppCall.posted`)।
- `(not confirmed on screen)` और `NOT_CONFIRMED` अब "हो गया" नहीं (`LiveFast.ok`, `DoneGate.notDoneYet`, `CallReply.resultNote`, `AgentRunner`)।
- कॉल काटना और स्पीकर अब बटन दबाकर दोबारा जाँचते हैं। न हुआ हो तो एक बार और कोशिश करते हैं, फिर सच बताते हैं: "मैंने दोबारा देखा, कॉल अभी कटी नहीं"।
- ऐप खुलने की जाँच दो बार होती है (`TruthCheck.twice`)। DoneGate को और काम वाले शब्द पहचानने सिखाए (रिमाइंडर, स्क्रीनशॉट, लॉक, स्पीकर, ऑर्डर…)।
- Live prompt में TRUTH नियम जोड़ा: पक्का न हो तो "एक सेकंड, मैं फिर से चेक करता हूँ" कहकर tool दोबारा चलाए।

5-टैब पट्टी (`ui/HomeTabs.kt`): होम • स्कैन (DK लेंस) • माइक (DK लाइव) • चैट • मेमोरी, सब पहले से बने रास्तों पर।
पट्टी शुरू से दिखती है, क्योंकि मालिक ने ख़ुद माँगी। छुपाने का स्विच `plus_hide_home_tabs` शुरू में बंद रहता है।
आवाज़ से: "DK, नीचे की पट्टी बंद करो" (दो बार पक्का पूछता है)। Tests: `TruthCheckTest` (12)।
