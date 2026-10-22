# AGENTS.md — DK2.0 में कोड बदलने के नियम

ये नियम हर कोडिंग एजेंट के लिए हैं (DK का अपना कोडिंग एजेंट और बाहर के AI एजेंट भी)।
DK का कोडिंग एजेंट यह फ़ाइल हर नए सेशन की शुरुआत में पढ़ता है। ये नियम **हर तरह के काम** पर लागू हैं —
नया फ़ीचर, बदलाव, या यूज़र का बताया कोई bug।

## 1. ब्रांच — `main` पर कभी सीधे काम नहीं

1. **पहले समझो:** बदलाव से पहले `.github/workflows/` (build, tests, release) और मौजूदा एजेंट
   (`CodingAgent.kt`, `CoderTeam.kt`, `AgentRunner.kt`, `GitHub.kt`, `SafePr.kt`, `Retry.kt`) पढ़ो।
2. **काम शुरू होते ही** (कोड छूने से पहले) `main` से नई branch `feature/dk-…` (या `feature/<नाम>`) बनाओ।
   सारा कोडिंग, बदलाव, push और टेस्टिंग इसी branch में। `main` को merge तक हाथ नहीं लगता।
   (DK ऐप में "सुरक्षित PR मोड" अब हमेशा चालू है — बंद नहीं किया जा सकता।)
3. काम में कुछ push नहीं हुआ (सिर्फ़ सवाल का जवाब) तो बनी हुई खाली branch हटा दो।

## 2. Pull Request — पुराने features बरकरार

4. काम पूरा होने पर उसी branch से `main` के लिए PR खोलो।
5. PR पर `PR build check` workflow **सारे** unit tests (`testDebugUnitTest`, पुराने + नए) और `assembleDebug` चलाता है।
   नया फ़ीचर सिर्फ़ तभी merge हो सकता है जब ये सब पास हों।
6. **पुराने tests कभी न हटाओ, न कमज़ोर करो** (assert हटाना, `@Test` कम करना, test skip करना) ताकि build पास हो जाए —
   code ठीक करो। एजेंट की अपनी जाँच (`SafePr.testGuard`) इसे पकड़ती है।
7. Pure Kotlin logic के लिए `app/src/test/java/com/dk/assistant/` में JUnit test जोड़ो या बढ़ाओ।

## 3. पुराने फ़ीचर में बदलाव या उसे हटाना

8. यूज़र **खुद** किसी पुराने फ़ीचर को बदलने/हटाने को कहे, तो यह जानबूझकर बदलाव है (नया-फ़ीचर जोखिम नहीं)।
9. ऐसे में **पहले backup**: `main` की अभी की हालत branch `backup/dk-…` में, **उसके बाद ही** बदलाव।
   Backup का नाम PR के विवरण में लिखा जाता है, ताकि ज़रूरत पड़ने पर उसी से फ़ीचर वापस लाया जा सके।
10. कोई मौजूदा फ़ाइल हटानी पड़े और backup नहीं बना हो, तो push से पहले backup अपने-आप बनता है।
11. फ़ीचर हटाया गया हो तो सिर्फ़ उसी के पुराने tests हटाना जायज़ है — बाकी किसी को नहीं।

## 4. टेस्टिंग — पहले जड़, फिर fix, फिर दोबारा test

12. PR खुलने के बाद GitHub Actions के tests + build देखो।
13. Fail हो तो: **पहले root cause** (log की बताई फ़ाइल/लाइन पढ़कर समझो क्यों fail हुआ) → **सिर्फ़ उसी वजह को ठीक करो** →
    उसी branch पर push → tests दोबारा। पास होने तक यह चक्र दोहराओ।
14. यही नियम यूज़र के बताए bug पर भी: पहले जड़ ढूँढो, उसके बाद ही fix। `done` में `root_cause` (फ़ाइल:लाइन – क्या गलत था, क्यों) देना ज़रूरी।
15. अंतहीन चक्र नहीं: वही गलती fix के बाद भी 3 बार लगातार लौटे (अटक गए), या 8 fix-राउंड हो जाएँ, तो रुककर यूज़र को साफ़ रिपोर्ट।

## 5. ऑटो-रिट्राई

16. कोई कदम अस्थायी वजह से fail हो — API कॉल (GitHub/AI: नेटवर्क, 5xx, timeout), या build/test (GitHub runner/नेटवर्क दिक्कत) —
    तो अपने-आप **2 बार और** (कुल 3) कोशिश, थोड़ा रुककर (1s, 2s, 4s…)। तब भी fail हो तभी यूज़र को रिपोर्ट।
17. असली गलतियों पर retry नहीं (गलत token/key, इजाज़त नहीं, सारी keys की limit खत्म, code की compile/test गलती) —
    ये तुरंत रिपोर्ट या "जड़ → fix" चक्र में जाती हैं।

## 6. Merge — सिर्फ़ यूज़र की मंज़ूरी से

18. Tests पास होने के बाद यूज़र को रिपोर्ट (क्या बदला, जड़, backup, PR link) और **merge की मंज़ूरी का इंतज़ार**।
    एजेंट खुद कभी merge नहीं करता।
19. मंज़ूरी ("Merge करें" बटन / "PR merge कर दो") मिलने पर फिर से जाँच: PR खुला, conflict नहीं, आखिरी commit के सारे checks हरे।
    इस बीच `main` आगे बढ़ गया हो तो पहले PR branch को नए `main` से update करके सारे tests दोबारा, पास हों तभी merge।
20. `main` में merge होते ही `Build DK APK` workflow अपने-आप नई APK release बनाता है।

## छेड़ना मना (जब तक मालिक साफ़ न कहे)

- पुराने चलते features: सिर्फ़ वही फ़ाइलें/लाइनें बदलो जो काम के लिए ज़रूरी हों। कोई refactor, rename या formatting नहीं।
- `.github/workflows/*`, `AGENTS.md`, `gradle.properties`, `settings.gradle*`, `gradlew`, keystore/signing फ़ाइलें।
- API keys या token कभी code में नहीं।

## English summary

Always start every task by creating a new `feature/*` branch from `main`; never commit to `main`. Open a PR to `main`; the PR check runs
ALL unit tests (old + new) and a debug build — never delete or weaken existing tests. If the user explicitly asks to change/remove an
existing feature, first create a `backup/dk-*` branch of `main`, then change it. On any failure (or user-reported bug) find and state the
root cause first, fix only that, re-test, and repeat until green (stop and report if the same error returns 3 times or after 8 rounds).
Transient failures (network/5xx/runner) are auto-retried up to 3 attempts before reporting. Merge only after the user approves a green PR;
if `main` moved, update the branch and re-run tests first. Merging to `main` triggers the APK release.
