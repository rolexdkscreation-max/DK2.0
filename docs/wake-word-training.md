# "हे डीके" / "हेलो डीके" वेक वर्ड - मॉडल बनाना (आसान कदम)

यह सिर्फ़ तब चाहिए जब आप मॉडल खुद दोबारा बनाना / सुधारना चाहें. ऐप चलाने के लिए कुछ नहीं करना -
ऐप पहली बार इंटरनेट से मॉडल अपने-आप ले लेता है (APK में कोई मॉडल नहीं, ऐप का साइज़ नहीं बढ़ता).

कोई API key, खाता या पैसा नहीं लगता. सब कुछ open source है:

| हिस्सा | क्या | लाइसेंस |
|---|---|---|
| openWakeWord (melspectrogram + embedding) | आवाज़ को अंकों में बदलना (साझा, ~2.4 MB) | Apache-2.0 |
| हमारा छोटा classifier (हर शब्द का एक) | "हे डीके" है या नहीं (~0.4 MB) | इसी रिपो का |
| Piper TTS (hi_IN, en_US libritts/vctk) | नकली बोलने वाले बनाना | MIT |
| edge-tts | और आवाज़ें (सिर्फ़ ट्रेनिंग के लिए, ऐप में नहीं) | - |
| LibriSpeech dev-clean | "दूसरी बातें" (गलत जागना रोकने को) | CC-BY-4.0 |
| openWakeWord ACAV100M features | घंटों की आम बातचीत (negative) | CC-BY-SA |

## कदम 1: कंप्यूटर तैयार करो (Linux / WSL, Python 3.10+)

```bash
python3 -m venv ~/ww && source ~/ww/bin/activate
pip install openwakeword==0.6.0 onnxruntime numpy scipy soundfile torch piper-tts edge-tts
mkdir -p ~/wk/models ~/wk/data ~/wk/gen ~/wk/feat ~/wk/out
cp tools/wake/*.py ~/wk/gen/
```

openWakeWord के दो साझा मॉडल `~/wk/models/` में रखो (नाम `melspectrogram.onnx`, `embedding_model.onnx`):
https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1

## कदम 2: बोलने के नमूने बनाओ (नकली आवाज़ें)

```bash
python ~/wk/gen/piper_gen.py      # Piper की सैकड़ों आवाज़ें (हिंदी + अंग्रेज़ी लहज़ा)
python ~/wk/gen/edge_gen.py       # edge-tts की हिंदी / भारतीय अंग्रेज़ी आवाज़ें
```

हर शब्द ("हे डीके", "हेलो डीके") के हज़ारों नमूने और "मिलते-जुलते पर गलत" शब्द
(जैसे "हे दीदी", "हेलो जी", "डीके", "हे भाई") बनते हैं.

अपनी असली आवाज़ भी डालनी हो: फ़ोन से 16 kHz WAV में 20-30 बार "हे डीके" बोलकर
`~/wk/gen/edge/train/hey/` में रख दो (हेलो डीके = `.../hello/`). असली आवाज़ से मॉडल सबसे ज़्यादा सुधरता है.

## कदम 3: negative डेटा

- LibriSpeech dev-clean: https://www.openslr.org/12 -> `~/wk/data/LibriSpeech/dev-clean`
- openWakeWord के तैयार features (Hugging Face `davidscripka/openwakeword_features`):
  `openwakeword_features_ACAV100M_2000_hrs_16bit.npy` का हिस्सा -> `~/wk/data/acav_part.npy`,
  और `validation_set_features.npy` (≈11 घंटे, गलत-जागना गिनने को) -> `~/wk/data/`

## कदम 4: अंक बनाओ (शोर + गूँज मिलाकर)

```bash
python ~/wk/gen/feat.py train
python ~/wk/gen/feat.py test
```

## कदम 5: मॉडल सिखाओ

```bash
python ~/wk/gen/train.py hey
python ~/wk/gen/train.py hello
```

नतीजा: `~/wk/out/hey_dk.onnx`, `~/wk/out/hello_dk.onnx` (हर एक ~0.4 MB).

## कदम 6: ऐप तक पहुँचाओ (बिना नया APK)

1. दोनों फ़ाइलें रिपो में `models/wake/v2/` (नया नंबर) में रखो.
2. `sha256sum models/wake/v2/*.onnx` और `stat -c %s` से SHA और साइज़ लो.
3. `models/wake/wake_models.json` में `"version"` एक बढ़ाओ और नई फ़ाइलों के `file` (जैसे `hey_dk_v2.onnx`), `urls`, `sha256`, `bytes` बदलो.
4. main में merge होते ही ऐप की Settings में "नया संस्करण देखो" -> "अपडेट करो" आएगा.
   SHA न मिले या डाउनलोड टूटे = पुराना मॉडल ही चलता रहता है.

## जाँच (फ़ोन पर)

Settings -> आवाज़ -> "वेक वर्ड" -> "जागने की जाँच": 10 सेकंड में "हे डीके" बोलिए, जागने का समय (ms) दिखेगा.

## ईमानदार बात

- नकली (TTS) आवाज़ों से बना मॉडल असली लोगों पर थोड़ा कम सही चलता है - अपनी 20-30 असली रिकॉर्डिंग डालने से फ़र्क़ पड़ता है.
- कोई वेक वर्ड 100% सही नहीं होता. TV / बातचीत से कभी-कभी गलत जागेगा - तब संवेदनशीलता "कम" कीजिए.
