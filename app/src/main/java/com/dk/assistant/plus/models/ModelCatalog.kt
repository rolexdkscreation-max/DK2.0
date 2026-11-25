package com.dk.assistant.plus.models

/**
 * 15 नवंबर 2026 (मालिक: "कोई भारी फ़ाइल ऐप के अंदर नहीं - APK का MB न बढ़े, सब इंटरनेट से"):
 * भारी मॉडल की सूची. कोई भी फ़ाइल APK (assets / res/raw) में नहीं जाती -
 * [ModelManager] इन्हें तभी डाउनलोड करता है जब मालिक वह सुविधा चालू करे, और `filesDir/models/` में रखता है.
 *
 * हर फ़ाइल का SHA-256 पहले से तय (pinned) - डाउनलोड के बाद मिलाया जाता है, न मिले तो फ़ाइल मिटा दी जाती है.
 * URL सिर्फ़ https और खुले (free) स्रोत: Hugging Face, GitHub (opencv_zoo).
 * Android import नहीं -> JVM test ([ModelCatalogTest]).
 */
object ModelCatalog {

    enum class Kind(val title: String) { VOICE("आवाज़-पहचान"), FACE("चेहरा-पहचान"), SOCIAL("सोशल पोस्ट (गैलरी पहचान)"), WAKE("वेक वर्ड"), STT("बोली से लिखना (IndicConformer)"),
        /** 19 नवंबर 2026: Supertonic 3 - फ़ोन पर बनने वाली आवाज़ ([com.dk.assistant.voice.st3.St3Config]) */
        TTS("बोलने वाली आवाज़ (Supertonic 3)"),
        /** 25 नवंबर 2026: DK ट्रांसलेटर का ऑफ़लाइन अनुवाद (IndicTrans2) */
        MT("ऑफ़लाइन अनुवाद (IndicTrans2)") }

    data class Asset(
        val id: String,
        val kind: Kind,
        val title: String,
        val sub: String,
        val fileName: String,
        val urls: List<String>,
        val sha256: String,
        val bytes: Long,
        val license: String,
    ) {
        val mb: String get() = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    }

    val REDIMNET_HEAVY = Asset(
        "redimnet2_b3", Kind.VOICE, "RedimNet2-B3 (ज़्यादा सटीक)", "Raw 16 kHz आवाज़ -> 192 अंक का voiceprint",
        "redimnet2_b3_vox2_lm.onnx",
        listOf("https://huggingface.co/soulmachine/evertranscript-redimnet2-b3-vox2-lm/resolve/main/redimnet2-b3-vox2-lm.onnx"),
        "dcecdce7d52bbd4739b24d0874359ec564d43f4b3a392f0104f505593b566d41", 18_045_013L, "MIT",
    )
    val REDIMNET_LIGHT = Asset(
        "redimnet_b2", Kind.VOICE, "RedimNet-B2 (कम CPU)", "हल्का नेटवर्क (1.8M params) - पुराने फ़ोन पर तेज़",
        "redimnet_b2_vox2.onnx",
        listOf("https://huggingface.co/OpenVoiceOS/redimnet-b2-vox2-onnx/resolve/main/redimnet_b2_vox2.onnx"),
        "a7586b34c8db1bbf32c64efc2b269be54f66787e50320c4472614bbe95218a81", 22_193_734L, "Apache-2.0",
    )
    /**
     * 21 नवंबर 2026 (मालिक: "परिवार की आवाज़ भी एकदम सटीक पहचाने"): ReDimNet2-B6, VoxBlinkV2+VoxCeleb2+CN-Celeb2
     * (कई भाषाएँ) पर सिखाया. Sandbox जाँच (LibriSpeech, 40 लोग, 1.5 s, बकबक-शोर 5 dB): गलती 1.26% - B2 8.1%, B3 6.3%.
     * Commit-pinned URL (बदल न सके). सिर्फ़ तब डाउनलोड जब मालिक Settings में "सबसे सटीक" चुने.
     */
    val REDIMNET_PRO = Asset(
        "redimnet2_b6", Kind.VOICE, "ReDimNet2-B6 (सबसे सटीक)", "Raw 16 kHz आवाज़ -> 192 अंक का voiceprint, शोर में भी पक्का",
        "redimnet2_b6_vb2_vox2_cnc2_lm.onnx",
        listOf("https://huggingface.co/lzrhahahahaha/ReDimNet2-ONNX/resolve/04f41495369d7798eb856999095c2b5f16c7ccc7/fp32/b6-vb2%2Bvox2%2Bcnc2_v0-lm.onnx"),
        "c297887a8c5a3e8df3fc9368da9ea37e64f0ea87218ec878eba8e9720478fd75", 51_130_836L, "MIT",
    )
    val FACE_EMBED = Asset(
        "sface_int8", Kind.FACE, "SFace चेहरा-पहचान", "चेहरे का 128 अंक का embedding (int8)",
        "sface_2021dec_int8.onnx",
        listOf(
            "https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec_int8.onnx",
            "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx",
        ),
        "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a", 9_896_933L, "Apache-2.0",
    )
    val FACE_DETECT = Asset(
        "yunet", Kind.FACE, "YuNet चेहरा-खोज", "फ़ोटो में चेहरा कहाँ है",
        "yunet_2023mar.onnx",
        listOf(
            "https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx",
            "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        ),
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4", 232_589L, "MIT",
    )


    /**
     * 16 नवंबर 2026 (मालिक: "सोशल मीडिया स्पेशलिस्ट - गैलरी से खुद फोटो/वीडियो चुने, मॉडल इंटरनेट से, 100 MB तक"):
     * Apple MobileCLIP2-S0 - बोले गए वर्णन ("समुद्र किनारे वाली फोटो") से गैलरी की सही फोटो/वीडियो, फ़ोन पर ही (कुल ~102 MB).
     * License: apple-amlr (Apple Machine Learning Research Model License) - निजी इस्तेमाल; Play Store पर बेचने के लिए नहीं.
     * जाँचा (sandbox): 38 golden वाक्यों पर tokenizer 100% मेल, embedding cosine >= 0.9986; बिना normalisation (0..1) वाली तस्वीर.
     */
    private const val MC2_TEXT = "https://huggingface.co/BambooHero/mobileclip2-s0-text-onnx/resolve/main/"
    val CLIP_VISION = Asset(
        "mobileclip2_s0_vision", Kind.SOCIAL, "MobileCLIP2-S0 तस्वीर-समझ", "फोटो / वीडियो का 512 अंक का embedding (256x256)",
        "mobileclip2_s0_vision.onnx",
        listOf("https://huggingface.co/felixhrdyn/mobileclip2-s0-onnx/resolve/main/vision_model.onnx"),
        "13d20ebfa8a8f63890eb2727fe4dc63009ff970f43e0f7d9d2ed999659f70c8a", 45_555_784L, "apple-amlr",
    )
    val CLIP_TEXT = Asset(
        "mobileclip2_s0_text", Kind.SOCIAL, "MobileCLIP2-S0 वाक्य-समझ", "बोले गए वर्णन का 512 अंक का embedding (int8)",
        "mobileclip2_s0_text.onnx", listOf(MC2_TEXT + "text_model.onnx"),
        "00576e5fad635fc09be196d7f2a6641cc1238a98e976bb8ac571d13898f5305f", 41_219_257L, "apple-amlr",
    )
    val CLIP_WORDS = Asset(
        "mobileclip2_s0_words", Kind.SOCIAL, "MobileCLIP2-S0 शब्द-तालिका", "49408 शब्द x 512 (int4)",
        "mobileclip2_s0_words_int4.bin", listOf(MC2_TEXT + "token_table_int4.bin"),
        "a232ca0f7af15f7fa9741cd246a7997d6985781812c69864dde9ae16e7197b88", 14_229_504L, "apple-amlr",
    )
    val CLIP_BPE = Asset(
        "clip_bpe_vocab", Kind.SOCIAL, "CLIP शब्द-टुकड़े (BPE)", "वाक्य को शब्द-टुकड़ों में बाँटना",
        "clip_bpe_simple_vocab_16e6.txt.gz", listOf(MC2_TEXT + "bpe_simple_vocab_16e6.txt.gz"),
        "924691ac288e54409236115652ad4aa250f48203de50a9e4722a6ecd48d6804a", 1_356_917L, "MIT",
    )
    /**
     * 16 नवंबर 2026 (मालिक: "हे डीके" / "हेलो डीके" - मॉडल ऐप में नहीं, इंटरनेट से): openWakeWord.
     * दो साझा मॉडल (आवाज़ -> अंक) openWakeWord v0.5.1 release से (Apache-2.0), और हमारे अपने दो छोटे classifier
     * जो DK2.0 रिपो की `models/wake/v1/` में हैं (APK के बाहर). नया संस्करण = [com.dk.assistant.wake.WakeModels].
     */
    const val WAKE_VERSION = 1
    private const val WAKE_REPO = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/models/wake/v1/"
    /** merge से पहले (PR वाला APK) - वही फ़ाइलें इस branch से */
    private const val WAKE_BRANCH = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/feature/dk-wake-word-v2/models/wake/v1/"
    val WAKE_MEL = Asset(
        "oww_mel", Kind.WAKE, "openWakeWord आवाज़-चित्र", "16 kHz आवाज़ -> mel (फ़ोन पर ही)",
        "oww_melspectrogram_v051.onnx",
        listOf("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx", WAKE_REPO + "melspectrogram.onnx", WAKE_BRANCH + "melspectrogram.onnx"),
        "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f", 1_087_958L, "Apache-2.0",
    )
    val WAKE_EMB = Asset(
        "oww_emb", Kind.WAKE, "openWakeWord आवाज़-अंक", "mel -> 96 अंक (हर 80 ms)",
        "oww_embedding_v051.onnx",
        listOf("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx", WAKE_REPO + "embedding_model.onnx", WAKE_BRANCH + "embedding_model.onnx"),
        "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f", 1_326_578L, "Apache-2.0",
    )
    val WAKE_HEY = Asset(
        "hey_dk", Kind.WAKE, "\"हे डीके\" पहचान", "हमारा छोटा classifier (v1)",
        "hey_dk_v1.onnx", listOf(WAKE_REPO + "hey_dk.onnx", WAKE_BRANCH + "hey_dk.onnx"),
        "de1f411a0592ecb6c825b04dd45a6630c4be135a14348a2a54e300da19870b36", 413_948L, "Apache-2.0",
    )
    val WAKE_HELLO = Asset(
        "hello_dk", Kind.WAKE, "\"हेलो डीके\" पहचान", "हमारा छोटा classifier (v1)",
        "hello_dk_v1.onnx", listOf(WAKE_REPO + "hello_dk.onnx", WAKE_BRANCH + "hello_dk.onnx"),
        "c66ab9b6af95971d1b0488046e646e6cfdffba96689042807f3c7aad7ea556b9", 413_948L, "Apache-2.0",
    )
    val WAKE: List<Asset> = listOf(WAKE_MEL, WAKE_EMB, WAKE_HEY, WAKE_HELLO)

    /**
     * 21 नवंबर 2026 (मालिक: "Sherpa-ONNX वाला मॉडल सही नहीं सुनता - हिंदी + English एकदम सही लिखे, मॉडल इंटरनेट से उतरे"):
     * पहले `mobilebytesensei/betterflow-hindi-streaming-ctc` था (FLEURS हिंदी पर ~29% शब्द ग़लत). अब AI4Bharat का
     * IndicConformer हिंदी (120M, CTC) - int8 रूप `ngiftsun/indicconformer-hi-int8-sherpa` (CC-BY-4.0, मूल AI4Bharat MIT).
     * हमारी जाँच: FLEURS हिंदी 4.4% शब्द ग़लत, Hinglish आदेश लगभग पूरे सही, चुप्पी पर कुछ नहीं. ~140 MB, सिर्फ़ Wi-Fi पर.
     * Commit-pinned URL (बदल न सके), साइज़ + SHA-256 Hugging Face से जाँचे. चलाता है ऐप का अपना onnxruntime (अलग .so नहीं).
     */
    private const val STT_REPO = "https://huggingface.co/ngiftsun/indicconformer-hi-int8-sherpa/resolve/7c3806fe01eb9b50784f09fe22cfeabf642c218e/"
    val STT_ENCODER = Asset(
        "stt_indic_hi_ctc", Kind.STT, "IndicConformer हिंदी + English", "फ़ोन पर ही बोली -> लिखावट (इंटरनेट / key नहीं)",
        "indic_hi_ctc_int8.onnx", listOf(STT_REPO + "model.int8.onnx"),
        "fc0c7db7c0e0c11d4ed540dc2cddca03a42fcba3568a0e0607c3631d87fa9f8a", 140_451_556L, "CC-BY-4.0",
    )
    val STT_TOKENS = Asset(
        "stt_indic_hi_tokens", Kind.STT, "IndicConformer शब्द-टुकड़े", "मॉडल के अक्षर / शब्द-टुकड़े",
        "indic_hi_ctc_tokens.txt", listOf(STT_REPO + "tokens.txt"),
        "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2", 67_605L, "CC-BY-4.0",
    )
    /** हटे Sherpa-ONNX (betterflow) मॉडल की फ़ाइलें - पुराने फ़ोन से [ModelManager.cleanupRemoved] मिटाता है (~66 MB खाली) */
    val OLD_STT_FILES = listOf("hi_en_stream_ctc_encoder_int8.onnx", "hi_en_stream_ctc_tokens.txt")
    val STT_SHERPA: List<Asset> = listOf(STT_ENCODER, STT_TOKENS)

    /**
     * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर ऑफ़लाइन भी चले - मॉडल इंटरनेट से डाउनलोड होने वाला"): AI4Bharat IndicTrans2
     * indic-indic distilled 320M, int8 ONNX (`hari31416/...-ONNX-int8`, MIT). मराठी <-> हिंदी (और बाकी भारतीय भाषाएँ).
     * sandbox जाँच: "तुम्ही कुठे जात आहात? मला स्टेशनला जायचं आहे." -> "आप कहाँ जा रहे हैं मुझे स्टेशन जाना है", ~0.2 s.
     * फ़ाइल-नाम वही रखे - .onnx के अंदर वज़न वाली .data फ़ाइल का नाम लिखा है (encoder_model.onnx.data, decoder_shared.onnx.data).
     * Commit-pinned, साइज़ + SHA-256 Hugging Face से जाँचे. कुल ~374 MB, सिर्फ़ Wi-Fi, सिर्फ़ ट्रांसलेटर में "डाउनलोड" दबाने पर.
     */
    private const val MT_REPO = "https://huggingface.co/hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8/resolve/b04956dee2f2a3e06e44bf09f8d654a6b81af99a/"
    private fun mt(id: String, title: String, file: String, remote: String, sha: String, bytes: Long) =
        Asset(id, Kind.MT, title, "DK ट्रांसलेटर - फ़ोन पर ही अनुवाद", file, listOf(MT_REPO + remote), sha, bytes, "MIT")
    val MT_ENC = mt("mt_it2_enc", "IndicTrans2 encoder", "encoder_model.onnx", "encoder_model.onnx",
        "0b965dfef8d5ba16603a9fbcc9d326acf6438e2768c52f6816004d9956e15810", 831_435L)
    val MT_ENC_DATA = mt("mt_it2_enc_data", "IndicTrans2 encoder वज़न", "encoder_model.onnx.data", "encoder_model.onnx.data",
        "914b82d0a08240d8cc133034a0958733ffc4d98b13a69111029ed8a85a9c159b", 120_068_096L)
    val MT_DEC = mt("mt_it2_dec", "IndicTrans2 decoder", "decoder_model.onnx", "decoder_model.onnx",
        "33f6dd13aff20d67cb659cc534b918f947c75691c7ee54a5494406f757923092", 2_037_642L)
    val MT_DEC_PAST = mt("mt_it2_dec_past", "IndicTrans2 decoder (cache)", "decoder_with_past_model.onnx", "decoder_with_past_model.onnx",
        "0f1f620993308f01f23460a60d534e3bd92e73d8d6e74141bcdb7ecbaaf86abb", 1_850_619L)
    val MT_DEC_DATA = mt("mt_it2_dec_data", "IndicTrans2 decoder वज़न", "decoder_shared.onnx.data", "decoder_shared.onnx.data",
        "5b35423deb71577d1bb95d7abb702042bdf5724846e8b21a8cb9f6757c54dfd7", 203_048_944L)
    val MT_TOK_SRC = mt("mt_it2_tok_src", "IndicTrans2 शब्द-टुकड़े (स्रोत)", "it2_tokenizer_src.json", "tokenizer_src.json",
        "d15ba1265a61b3b66122aab74e888442093a2c711f51b0f9896108661e1d346c", 23_876_630L)
    val MT_TOK_TGT = mt("mt_it2_tok_tgt", "IndicTrans2 शब्द-टुकड़े (अनुवाद)", "it2_tokenizer_tgt.json", "tokenizer_tgt.json",
        "51b05cd37636765e158146534d54452be558df32c6ab06ba4caeecdd0266a580", 23_869_629L)
    val MT_INDIC: List<Asset> = listOf(MT_ENC, MT_ENC_DATA, MT_DEC, MT_DEC_PAST, MT_DEC_DATA, MT_TOK_SRC, MT_TOK_TGT)

    /** हटा हुआ Whisper small (q5_1) - पुराने फ़ोन में पड़ी फ़ाइल; Settings का बटन इसे मिटाता है (जगह खाली) */
    const val OLD_WHISPER_FILE = "ggml_small_q5_1.bin"
    const val OLD_WHISPER_BYTES = 190_085_487L

    /** यह मॉडल सिर्फ़ Wi-Fi (बिना-गिनती वाले नेट) पर उतरे? - बोली वाला मॉडल हाँ (मालिक: "Wi-Fi पर अलग से Download") */
    fun wifiOnly(a: Asset): Boolean = a.kind == Kind.STT || a.kind == Kind.MT

    val SOCIAL_CLIP: List<Asset> = listOf(CLIP_VISION, CLIP_TEXT, CLIP_WORDS, CLIP_BPE)

    /**
     * 15 नवंबर 2026 (मालिक: "3D कैरेक्टर पूरी तरह हटा दो, अच्छा नहीं है"): कैरेक्टर (.glb) और GLTFLoader सूची से हटे.
     * जिन फ़ोन में पहले डाउनलोड हो चुके थे, वहाँ से ये फ़ाइलें [ModelManager.cleanupRemoved] चुपचाप मिटाता है (जगह खाली हो).
     */
    val REMOVED_FILES = listOf("robot_expressive.glb", "cesium_man.glb", "brainstem.glb", "fox.glb", "three_gltf_loader.js", "three_bg_utils.js") + OLD_STT_FILES

    val ALL: List<Asset> = listOf(REDIMNET_HEAVY, REDIMNET_LIGHT, REDIMNET_PRO, FACE_EMBED, FACE_DETECT) + SOCIAL_CLIP + WAKE + STT_SHERPA + MT_INDIC +
        com.dk.assistant.voice.st3.St3Config.ALL + com.dk.assistant.voice.st3.St3Config.FAST +
        com.dk.assistant.voice.st3.St3Config.MALE.map { it.asset }

    fun byId(id: String): Asset? = ALL.firstOrNull { it.id == id }
    fun ofKind(k: Kind): List<Asset> = ALL.filter { it.kind == k }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    /** catalog की अपनी जाँच (test भी यही चलाता है): https, सही SHA, सुरक्षित फ़ाइल-नाम, दोहराव नहीं */
    fun problems(list: List<Asset> = ALL): List<String> = buildList {
        val ids = HashSet<String>(); val files = HashSet<String>()
        list.forEach { a ->
            if (!ids.add(a.id)) add("${a.id}: id दोहराया")
            if (!files.add(a.fileName)) add("${a.id}: फ़ाइल-नाम दोहराया")
            if (a.urls.isEmpty() || a.urls.any { !it.startsWith("https://") }) add("${a.id}: URL https नहीं")
            if (!HEX64.matches(a.sha256)) add("${a.id}: SHA-256 गलत")
            if (a.bytes <= 0) add("${a.id}: साइज़ नहीं")
            if (!a.fileName.matches(Regex("^[a-z0-9_.]+$")) || a.fileName.contains("..")) add("${a.id}: फ़ाइल-नाम असुरक्षित")
        }
    }
}
