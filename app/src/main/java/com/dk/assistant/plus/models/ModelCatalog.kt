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

    enum class Kind(val title: String) { VOICE("आवाज़-पहचान"), FACE("चेहरा-पहचान"), SOCIAL("सोशल पोस्ट (गैलरी पहचान)") }

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
    val SOCIAL_CLIP: List<Asset> = listOf(CLIP_VISION, CLIP_TEXT, CLIP_WORDS, CLIP_BPE)

    /**
     * 15 नवंबर 2026 (मालिक: "3D कैरेक्टर पूरी तरह हटा दो, अच्छा नहीं है"): कैरेक्टर (.glb) और GLTFLoader सूची से हटे.
     * जिन फ़ोन में पहले डाउनलोड हो चुके थे, वहाँ से ये फ़ाइलें [ModelManager.cleanupRemoved] चुपचाप मिटाता है (जगह खाली हो).
     */
    val REMOVED_FILES = listOf("robot_expressive.glb", "cesium_man.glb", "brainstem.glb", "fox.glb", "three_gltf_loader.js", "three_bg_utils.js")

    val ALL: List<Asset> = listOf(REDIMNET_HEAVY, REDIMNET_LIGHT, FACE_EMBED, FACE_DETECT) + SOCIAL_CLIP

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
