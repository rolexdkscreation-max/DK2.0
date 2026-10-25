# v3.69 बैकअप - सेटिंग्स से हटाए हिस्से

ये फ़ाइलें v3.68 (main 2f83f27) की **ज्यों-की-त्यों** प्रतियाँ हैं, बदलाव से पहले. ऐप में compile नहीं होतीं (`.bak`).

| फ़ाइल | क्या हटा |
|---|---|
| `SettingsActivity.kt.bak` | पुराना "आवाज़" हिस्सा: महिला/पुरुष, आवाज़ का प्रकार, चुनी आवाज़ की सूची, बोलने का अंदाज़, "आवाज़ चुनें"/"आवाज़ सुनें", स्पीड, पिच, फ़ोन की TTS सेटिंग (`secVoice`, `fillGeminiList`, `updateVoiceText`, `chooseVoice`, `testVoice`, `preview`) + प्रोफ़ाइल का "पर्सनैलिटी" card |
| `Personality.kt.bak` | 10 पर्सनैलिटी (enum) - फ़ाइल हटाई |
| `PersonalityLanguageTest.kt.bak` | पर्सनैलिटी वाले tests |
| `BrainManager.kt.bak`, `Prefs.kt.bak` | `Personality.promptLine(...)`, `Prefs.personalityPreset()` |
| `VoiceEngineCard.kt.bak` | नमूना-वाक्य का डिब्बा, "तीनों बारी-बारी सुनो (तुलना)", "रोको" |

वापस लाना हो: `git show 2f83f27:<path>` या यहाँ से कॉपी. पहले सेव किए मान (voice_gender, tts_rate, personality_preset …) फ़ोन में वैसे ही रहते हैं.
