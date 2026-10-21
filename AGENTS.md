# AGENTS.md — DK2.0 में कोड बदलने के नियम

ये नियम हर कोडिंग एजेंट के लिए हैं (DK का अपना कोडिंग एजेंट और बाहर के AI एजेंट भी)।
DK का कोडिंग एजेंट यह फ़ाइल हर नए सेशन की शुरुआत में पढ़ता है।

## काम करने का तरीका (सुरक्षित PR मोड)

1. **पहले समझो:** बदलाव से पहले `.github/workflows/` (build, tests, release) और मौजूदा एजेंट
   (`CodingAgent.kt`, `CoderTeam.kt`, `AgentRunner.kt`, `GitHub.kt`) पढ़ो।
2. **`main` पर कभी सीधे push नहीं:** हर काम की नई branch `feature/dk-…` (या `feature/<नाम>`) में होता है।
3. **Tests:** pure Kotlin logic के लिए `app/src/test/java/com/dk/assistant/` में JUnit test जोड़ो या बढ़ाओ।
4. **Pull Request:** branch से `main` के लिए PR बनाओ। `PR build check` workflow उस पर
   `testDebugUnitTest` (unit tests) और `assembleDebug` (debug APK) चलाता है।
5. **Build logs:** check fail हो तो उसका log पढ़ो, असली वजह ठीक करो और उसी branch पर फिर push करो।
6. **Merge सिर्फ़ review के बाद:** मालिक PR देखकर ही merge करता है। check हरा न हो तो merge नहीं।
   `main` में merge होते ही `Build DK APK` नई release बना देता है।

## छेड़ना मना (जब तक मालिक साफ़ न कहे)

- पुराने चलते features: सिर्फ़ वही फ़ाइलें/लाइनें बदलो जो काम के लिए ज़रूरी हों। कोई refactor, rename या formatting नहीं।
- `.github/workflows/*`, `AGENTS.md`, `gradle.properties`, `settings.gradle*`, `gradlew`, keystore/signing फ़ाइलें।
- API keys या token कभी code में नहीं।

## English summary

Read workflows and existing agents first, work only on a new `feature/*` branch, add unit tests for pure logic,
open a PR to `main`, fix failures from the PR build log on the same branch, and merge only after the owner reviews
a green PR. Never touch unrelated working code or the protected files above unless explicitly asked.
