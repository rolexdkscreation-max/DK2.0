# DK – आवाज़ से पूरा फ़ोन कंट्रोल करने वाला AI असिस्टेंट

DK एक Android ऐप है जो:
- आपकी आवाज़ सुनता है (Hindi + English, फ्री Google speech recognition)
- स्क्रीन "देखता" है (Accessibility से सारे बटन/टेक्स्ट + Android 11+ पर screenshot)
- Gemini AI (फ्री API key) से सोचता है कि क्या करना है
- खुद click, type, scroll, swipe, back/home करता है
- फ्री, नेचुरल आवाज़ में जवाब देता है (Android/Google TTS, कोई पैसा नहीं)

## यह कैसे काम करता है

```
आप बोलते हो  →  SpeechRecognizer (आवाज़ → टेक्स्ट)
                     ↓
         DKAccessibilityService स्क्रीन पढ़ता है (+ screenshot)
                     ↓
         GeminiBrain: "command + स्क्रीन" Gemini को भेजता है
                     ↓
         Gemini JSON लौटाता है: {"say": "...", "actions": [...], "done": false}
                     ↓
         AgentRunner actions चलाता है (click/type/scroll/open_app...)
         Speaker (TTS) "say" बोलता है
                     ↓
         काम पूरा नहीं? → फिर से स्क्रीन पढ़ो → Gemini → action  (max 20 steps)
```

## फ़ोल्डर स्ट्रक्चर

```
DK/
├── .github/workflows/build.yml      ← GitHub पर APK बनाने वाली मशीन
├── .gitignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── drawable/ic_dk.xml, bubble_bg.xml
        │   ├── layout/activity_main.xml
        │   ├── values/strings.xml, themes.xml
        │   └── xml/accessibility_config.xml
        └── java/com/dk/assistant/
            ├── MainActivity.kt          ← सेटअप स्क्रीन
            ├── DKAccessibilityService.kt← आँख + हाथ + फ्लोटिंग बबल
            ├── AgentRunner.kt           ← देखो → सोचो → करो लूप
            ├── GeminiBrain.kt           ← AI दिमाग (Gemini API)
            ├── AppLauncher.kt           ← ऐप खोलना, YouTube/Google search
            ├── Speaker.kt               ← फ्री नेचुरल TTS
            ├── VoiceListener.kt         ← फ्री speech-to-text
            ├── KeepAliveService.kt      ← बैकग्राउंड में चालू रखना
            ├── Prefs.kt                 ← सेटिंग्स सेव
            └── DKLog.kt                 ← लॉग
```
