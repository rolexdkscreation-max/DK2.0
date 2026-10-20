# DK – आवाज़ से पूरा फ़ोन कंट्रोल करने वाला AI असिस्टेंट

DK एक Android ऐप है जो:
- आपकी आवाज़ सुनता है (Hindi + English, फ्री Google speech recognition)
- स्क्रीन "देखता" है (Accessibility से सारे बटन/टेक्स्ट + Android 11+ पर screenshot)
- Gemini AI (फ्री API key) से सोचता है कि क्या करना है
- खुद click, type, scroll, swipe, back/home करता है
- फ्री, नेचुरल आवाज़ में जवाब देता है (Android/Google TTS, कोई पैसा नहीं)


## नया (v2.0)
- लिक्विड ग्लास UI, बीच में 3D ओर्ब (छोटे बिंदुओं से बना पृथ्वी जैसा गोला, घूमते सैटेलाइट; रंग बताते हैं DK सुन/सोच/बोल रहा है)
- सेटिंग्स पैनल: आपका नाम, असिस्टेंट का नाम, पर्सनैलिटी, जगाने वाले शब्द, आवाज़ की स्पीड/पिच
- कई AI: Gemini, Groq, OpenRouter, Mistral, Z.ai (फ्री) + OpenAI, xAI Grok (पेड) + Custom API
- हर AI के लिए API key, मॉडल, "टेस्ट करें" बटन; एक फेल हो तो दूसरी अपने-आप
- नीचे हमेशा दिखने वाला "सेव करें" बटन + अपने-आप सेव

## अपडेट (v2.2 से)
- हर push पर GitHub Actions APK बनाकर एक GitHub Release (`v<number>`) बनाता है
- DK → सेटिंग्स → अपडेट: नया version दिखता है, वहीं से डाउनलोड + इंस्टॉल
- सभी APK एक ही key से sign होते हैं (GitHub Secrets: DK_KEYSTORE_B64, DK_KS_PASS) — इन्हें मत हटाना

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
            ├── BrainManager.kt          ← AI दिमाग (कई कंपनियाँ + fallback)
            ├── AiClients.kt             ← Gemini / OpenAI-जैसे API कॉल
            ├── Providers.kt             ← AI कंपनियों की लिस्ट
            ├── SettingsActivity.kt      ← ग्लास सेटिंग्स पैनल
            ├── OrbView.kt               ← 3D बिंदुओं वाला ओर्ब
            ├── AuroraView.kt            ← चलता हुआ रंगीन बैकग्राउंड
            ├── AppLauncher.kt           ← ऐप खोलना, YouTube/Google search
            ├── Speaker.kt               ← फ्री नेचुरल TTS
            ├── VoiceListener.kt         ← फ्री speech-to-text
            ├── KeepAliveService.kt      ← बैकग्राउंड में चालू रखना
            ├── Prefs.kt                 ← सेटिंग्स सेव
            └── DKLog.kt                 ← लॉग
```
