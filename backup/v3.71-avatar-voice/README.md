# v3.71 बैकअप (लड़की अवतार + बोलने की देरी)

इस बदलाव से पहले की असली फ़ाइलें (main 39057d9 से). कुछ हटाया नहीं गया - सिर्फ़ जोड़ा/बदला गया.
वापस लाना हो तो: `.bak` हटाकर फ़ाइल को उसकी जगह रख दें (जैसे `GeminiLive.kt.bak` -> `app/src/main/java/com/dk/assistant/GeminiLive.kt`,
`SpeechPipeline.kt.bak` / `GeminiTtsEngine.kt.bak` / `EdgeTtsEngine.kt.bak` -> `app/src/main/java/com/dk/assistant/voice/engine/`,
`activity_main.xml.bak` -> `app/src/main/res/layout/`, `AndroidManifest.xml.bak` -> `app/src/main/`)
और नई फ़ाइलें `avatar/` फ़ोल्डर, `LatencyBoard.kt`, `VoiceWarm.kt` हटा दें.
