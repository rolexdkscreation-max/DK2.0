package com.dk.assistant.voice.st3

import com.dk.assistant.plus.models.ModelCatalog

/**
 * 19 नवंबर 2026 (मालिक, चरण 1): Supertonic 3 - फ़ोन पर ही बनने वाली आवाज़ (ONNX Runtime). सारे URL यहीं, एक जगह.
 *
 *  - कोई मॉडल APK में नहीं. सब Hugging Face repo `Supertone/supertonic-3` से, **एक तय commit** पर पिन
 *    ([COMMIT]) - GitHub वाली रिपॉज़िटरी (supertone-inc/supertonic) आर्काइव हो चुकी है, आगे कुछ बदले तो भी हमारी फ़ाइलें वही रहें.
 *  - साइज़ और SHA-256: बड़ी (.onnx) फ़ाइलों के Hugging Face API (lfs.oid) से; छोटी JSON फ़ाइलें उसी commit से उतारकर
 *    sha256sum से निकाले (19 नवंबर 2026). डाउनलोड के बाद [com.dk.assistant.plus.models.ModelManager] फिर मिलाता है.
 *  - सिर्फ़ महिला आवाज़ें F1-F5. पुरुष (M1-M5) न सूची में, न डाउनलोड.
 *  - फ़ाइलें `filesDir/models/st3_*` में ([ModelManager] की जगह). नाम "supertonic..." से शुरू नहीं - पुराने हटाए गए
 *    Supertonic की एक-बार वाली सफ़ाई ([com.dk.assistant.voice.engine.VoiceEngines.wipeOldOffline]) इन्हें नहीं छूती.
 *
 * Android import नहीं -> JVM test ([St3ConfigTest]).
 */
object St3Config {
    const val REPO = "Supertone/supertonic-3"
    /** 19 नवंबर 2026 को repo का आखिरी commit (lastModified 2026-05-18) */
    const val COMMIT = "3cadd1ee6394adea1bd021217a0e650ede09a323"
    const val BASE = "https://huggingface.co/$REPO/resolve/$COMMIT/"
    const val LICENSE = "OpenRAIL-M"

    const val LANG = "hi"
    const val SAMPLE_RATE = 44_100

    const val SPEED_DEF = 1.05f
    const val SPEED_MIN = 0.70f
    const val SPEED_MAX = 1.60f
    const val STEPS_DEF = 8
    const val STEPS_MIN = 2
    const val STEPS_MAX = 16

    const val DEFAULT_VOICE = "F1"
    const val TEST_LINE = "नमस्ते, मैं डीके हूँ। यह मेरी नई आवाज़ है, जो आपके फ़ोन पर ही बन रही है। आपको कैसी लगी?"

    /** इससे बड़ी फ़ाइल मोबाइल डेटा पर हो तो पहले पूछो */
    const val ASK_ON_MOBILE_BYTES = 20L * 1024 * 1024

    private val K = ModelCatalog.Kind.TTS

    private fun a(id: String, title: String, sub: String, path: String, sha: String, bytes: Long) =
        ModelCatalog.Asset(id, K, title, sub, "st3_" + path.substringAfterLast('/'), listOf(BASE + path), sha, bytes, LICENSE)

    val DP = a("st3_dp", "Supertonic 3: लंबाई", "हर अक्षर कितनी देर बोले", "onnx/duration_predictor.onnx",
        "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db", 3_700_147L)
    val TEXT_ENC = a("st3_text", "Supertonic 3: अक्षर समझ", "text -> अंक", "onnx/text_encoder.onnx",
        "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff", 36_416_150L)
    val VECTOR = a("st3_vector", "Supertonic 3: आवाज़ का ढाँचा", "सबसे बड़ा हिस्सा (कदम-दर-कदम आवाज़ बनाता है)", "onnx/vector_estimator.onnx",
        "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c", 256_534_781L)
    val VOCODER = a("st3_vocoder", "Supertonic 3: आवाज़ की लहर", "ढाँचे -> असली आवाज़ (44.1 kHz)", "onnx/vocoder.onnx",
        "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba", 101_424_195L)
    val CFG = a("st3_cfg", "Supertonic 3: सेटिंग फ़ाइल", "tts.json", "onnx/tts.json",
        "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09", 8_253L)
    val INDEXER = a("st3_indexer", "Supertonic 3: अक्षर-सूची", "unicode_indexer.json", "onnx/unicode_indexer.json",
        "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f", 277_676L)

    /** इंजन (सारी आवाज़ों में एक ही) */
    val CORE: List<ModelCatalog.Asset> = listOf(DP, TEXT_ENC, VECTOR, VOCODER, CFG, INDEXER)

    data class Voice(val id: String, val name: String, val asset: ModelCatalog.Asset)

    private fun v(id: String, name: String, sha: String, bytes: Long) =
        Voice(id, name, ModelCatalog.Asset("st3_voice_${id.lowercase()}", K, "Supertonic 3 आवाज़ $id ($name)", "महिला आवाज़",
            "st3_voice_${id.lowercase()}.json", listOf(BASE + "voice_styles/$id.json"), sha, bytes, LICENSE))

    /** सिर्फ़ महिला आवाज़ें (क्रम = स्क्रीन पर क्रम) */
    val VOICES: List<Voice> = listOf(
        v("F1", "आवाज़ 1", "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2", 292_046L),
        v("F2", "आवाज़ 2", "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6", 292_423L),
        v("F3", "आवाज़ 3", "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab", 290_794L),
        v("F4", "आवाज़ 4", "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b", 291_808L),
        v("F5", "आवाज़ 5", "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d", 291_479L),
    )

    val ALL: List<ModelCatalog.Asset> = CORE + VOICES.map { it.asset }

    fun voice(id: String?): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first { it.id == DEFAULT_VOICE }
    fun coreBytes(): Long = CORE.sumOf { it.bytes }

    /** pure: जो आवाज़ सूची में नहीं (या पुरुष) -> डिफ़ॉल्ट F1 */
    fun cleanVoice(id: String?): String = voice(id).id
    fun cleanSpeed(s: Float): Float = if (s.isNaN()) SPEED_DEF else s.coerceIn(SPEED_MIN, SPEED_MAX)
    fun cleanSteps(n: Int): Int = n.coerceIn(STEPS_MIN, STEPS_MAX)

    /** pure: मोबाइल डेटा (metered) पर इतना बड़ा डाउनलोड = पहले पूछो */
    fun askBeforeDownload(metered: Boolean, bytesLeft: Long): Boolean = metered && bytesLeft >= ASK_ON_MOBILE_BYTES
}
