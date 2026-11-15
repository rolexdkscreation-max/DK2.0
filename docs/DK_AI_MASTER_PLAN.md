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
| Settings → "DK AI Plus" (मुख्य + एडवांस्ड) | `plus/PlusSettingsActivity.kt` | बना - जो अभी नहीं जुड़ा वह "अगला चरण" (disabled) |

## पहले से मौजूद (सुधारना है, दोबारा नहीं बनाना)

फ़ेस (YuNet/SFace डाउनलोड), मालिक की आवाज़ (CAM++), टच मोड (Guard), फ़ोन लॉक/अनलॉक, ऑटो रिप्लाई, फ़्लोटिंग ओर्ब,
Edge Glow, JARVIS मोड, DK स्वभाव (Persona), भाषा (hinglish/hi/en/auto), बैकअप (Google Drive), Whiteboard/Teacher,
PDF (ComputerOffice), Live Vision, परमिशन, कोडिंग एजेंट, सोशल रिव्यू.

## आगे के चरण

- **Phase 2**: 5-tab bottom nav (Home/Scan/Mic/Memories/Chat) + side drawer; Friday/Venom personas; 20+ भाषाएँ;
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
