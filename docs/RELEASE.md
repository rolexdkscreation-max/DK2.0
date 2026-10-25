# DK — APK बनाना और Release (DK AI Part 15)

## 1. अपने-आप release (GitHub, पहले से चालू)
`main` पर हर merge के बाद `.github/workflows/build.yml`:
1. version तय करता है — `version.txt` आधार है; commit में `[software]` या नई स्क्रीन/नई `.kt` फ़ाइल = `x.(y+1)`, बाकी `[security]` = `x.y.(z+1)`।
   `versionCode` = GitHub run नंबर (`VERSION_CODE`)।
2. `gradle assembleRelease` से APK बनाता है।
3. signing: repo secrets `DK_KEYSTORE_B64` (keystore base64) + `DK_KS_PASS`, alias `dk`।
   secret न हो तो debug key से sign होता है (warning आती है) — तब पुराने app के ऊपर update install **नहीं** होगा।
4. GitHub Release `v<run>` बनाता है, `DK.apk` जोड़ता है — ऐप के अंदर "अपडेट" इसी से नया version देखता है।

कुछ भी हाथ से नहीं करना पड़ता। **keystore कभी repo में न डालें** (`*.jks`, `*.keystore`, `keystore.properties` `.gitignore` में हैं)।

## 2. अपने कंप्यूटर पर signed APK
ज़रूरत: JDK 17, Android SDK (compileSdk 34), Gradle 8.7।

repo की जड़ में `keystore.properties` बनाएँ (git-ignored):
```properties
storeFile=/पूरा/रास्ता/dk.jks
storePassword=********
keyAlias=dk
keyPassword=********
```
फिर:
```bash
VERSION_CODE=1000 gradle :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
`DK_KEYSTORE` / `DK_KS_PASS` env हों तो वे `keystore.properties` से पहले माने जाते हैं (CI वाला तरीका)।
**वही keystore** इस्तेमाल करें जो GitHub secret में है, वरना फ़ोन पर update नहीं चढ़ेगा।

## 3. छोटा APK (R8 minify) — अभी opt-in
```bash
DK_MINIFY=1 gradle :app:assembleRelease
```
यह code shrink + resource shrink चालू करता है (`app/proguard-rules.pro`)। default **बंद** है क्योंकि
असली फ़ोन पर पूरी जाँच नहीं हुई। चालू करने से पहले नीचे की checklist minified APK पर चलाएँ;
सब ठीक हो तो `build.yml` में `DK_MINIFY: 1` जोड़ा जा सकता है (workflow मालिक की मंज़ूरी से ही बदले)।

## 4. हाथ से GitHub Release (ज़रूरत पड़े तो)
```bash
gh release create v<नंबर> app/build/outputs/apk/release/app-release.apk#DK.apk \
  --repo rolexdkscreation-max/DK2.0 --title "DK <version>" --notes "क्या बदला" --latest
```
ऐप का updater सबसे नई (`--latest`) release का `DK.apk` लेता है, इसलिए asset का नाम `DK.apk` ही रखें।

## 5. Permissions — क्यों चाहिए
| Permission | किसलिए |
|---|---|
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | DK लाइव में सुनना |
| Accessibility service | आपके कहने पर फ़ोन चलाना (टैप, टाइप, स्क्रॉल), स्क्रीन पढ़ना, तैरता बबल और किनारों की चमक |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | स्क्रीन शेयर (आप खुद चालू करें तभी) |
| `POST_NOTIFICATIONS` | "DK सुन रहा है", वेबसाइट तैयार जैसी सूचनाएँ |
| Notification listener | आपके पूछने पर आए notification बताना / आपके कहने पर reply |
| `READ_PHONE_STATE`, `READ_CALL_LOG`, `ANSWER_PHONE_CALLS`, `CALL_PHONE` | कॉल बताना, उठाना/काटना, बोलकर कॉल करना |
| `READ_CONTACTS` | नाम से कॉल/मैसेज |
| `CAMERA` | सिर्फ़ टॉर्च |
| `ACCESS_COARSE/FINE_LOCATION` | मौसम/इलाके की बात (सेव नहीं होती) |
| `READ_MEDIA_IMAGES` (वैकल्पिक) | "सबसे नई फ़ोटो दिखाओ" — सिर्फ़ पढ़ना, मिटाना नहीं |
| `REQUEST_INSTALL_PACKAGES` | ऐप के अंदर से update install |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | पीछे चलते रहना, फ़ोन चालू होने पर तैयार |
| `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` (Android 11 तक) | आवाज़ का रास्ता, Bluetooth headset का माइक |
| `WRITE_SECURE_SETTINGS` (सिर्फ़ adb से दिया जाए तो) | Accessibility अपने-आप चालू रखना |

## 6. Release से पहले जाँच (असली फ़ोन पर)
- [ ] पुराने version के ऊपर install — सेटिंग्स, keys, यादें बनी रहें
- [ ] नया install — परिचय (4 पेज) दिखे, "छोड़ें" काम करे; दोबारा खोलने पर न दिखे
- [ ] DK लाइव: सुनना, बोलना, बीच में टोकना, म्यूट
- [ ] Accessibility: "WhatsApp खोलो", कॉल करना; मैसेज/पोस्ट से पहले "हाँ" पूछे
- [ ] ओर्ब: सब डॉट वाले आकार; पुरुष/महिला HD चेहरा साफ़ दिखे और फ़ोन धीमा न हो (3200 डॉट); किनारों की चमक सुनते/बोलते समय
- [ ] Website Forge: एक वेबसाइट बनाकर सेव/शेयर
- [ ] Settings → ऐप के बारे में: version और "Created by धीरज कुमार सिंह"
- [ ] अपडेट: Settings → अपडेट से नया version मिले
- [ ] सिर्फ़ मेरी आवाज़: "मेरी आवाज़ सिखाओ" (मॉडल डाउनलोड + 5 वाक्य), "जाँचो" में अपनी और किसी और की आवाज़; DK लाइव में किसी और के बोलने पर जवाब न आए, आपकी बात पर पहले जितना तेज़ आए; दूसरे कमरे की आवाज़ अनसुनी
- [ ] तेज़ और सच्चे काम: "यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग प्ले कर दो" → गाना बजे, फिर ही "हो गया"; WhatsApp संदेश (पूछकर और "बिना पूछे" चालू करके) सच में जाए; "आखिरी फोटो डिलीट करो" → Android का Allow, फ़ोटो ट्रैश में
- [ ] (`DK_MINIFY=1` वाला APK हो तो) ऊपर की सारी जाँच दोबारा

## 7. Tests
```bash
gradle :app:testDebugUnitTest     # सारे JVM tests (हर PR पर check.yml चलाता है)
gradle :app:assembleDebug
```

## जानी हुई कमी
`build.yml` के emulator step में `adb shell am start -n com.dk.app/.MainActivity` लिखा है, जबकि package
`com.dk.assistant` है (`|| true` की वजह से step पास दिखता है, पर ऐप असल में खुलता नहीं)। workflow मालिक की
मंज़ूरी से ही बदलेगा — सही command: `adb shell am start -n com.dk.assistant/.MainActivity`।
