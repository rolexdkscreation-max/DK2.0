# व्यवहार वाले tests (behavior tests)

यहाँ के tests code की लाइन नहीं ढूँढते — ऐप का असली हिस्सा चलाकर **नतीजा** देखते हैं
(Robolectric: फ़ोन के बिना असली Context, SharedPreferences, Activity, View)।

| पुराना तरीका (source-check) | नया तरीका (behavior) |
|---|---|
| `src("SettingsActivity.kt").contains("putBoolean(\"dk_only_listen\"")` | Settings खोलो → switch दबाओ → बाहर जाओ → `Prefs.dkOnly()` सच में `true`? |
| code बँटे / नाम बदले = test टूटे, जबकि ऐप ठीक | code कैसे भी लिखा हो, ऐप ठीक = test पास |
| गलत logic पर भी पास (लाइन मौजूद है) | गलत नतीजा = test fail |

## नया test लिखते समय
- class पर `@RunWith(RobolectricTestRunner::class)` और `@Config(sdk = [34], application = Application::class)`।
  (`DKApp` main thread पर अपना loop चलाता है, उससे Robolectric रुक जाता है — इसलिए सादा `Application`।)
- स्क्रीन की चीज़ें `Ui.switch(activity, "दिखने वाला text")` / `Ui.field(activity, "label")` से ढूँढें — variable के नाम से नहीं।
- सेव होना जाँचने के लिए `activity.pause()` (Settings `onPause` में सेव करती है) फिर `Prefs.x(context)`।
- हर test में SharedPreferences नए सिरे से खाली होती हैं; Kotlin `object` (जैसे `CoderSession`) नहीं — उन्हें `@Before` में साफ़ करें।

## पुराने source-check tests
हटाए नहीं गए (नियम: पुराने test न हटाएँ, न कमज़ोर करें)। जब किसी हिस्से का behavior test बन जाए, तब
भी पुराना रहता है; मालिक की हाँ से ही बाद में उसे हटाया जा सकता है।
Source-check अब सिर्फ़ वहीं ठीक है जहाँ चलाकर जाँचना सच में मुमकिन न हो (जैसे manifest की permission)।
