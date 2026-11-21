package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक: "Live का caption फ़ोन पर Sherpa-ONNX से, अलग key नहीं"): "आप:" वाली लाइन लिखने वाले का
 * साझा रूप. [SherpaCaption] (फ़ोन पर, पहले) और [LiveCaption] (caption key वाला, सिर्फ़ बैकअप) - दोनों यही करते हैं,
 * ताकि GeminiLive / Ear का बाकी कोड जैसा था वैसा रहे.
 */
interface CaptionSource {
    /** लिखने के लिए तैयार (Sherpa: मॉडल खुला; LiveCaption: जुड़ गया) */
    val ready: Boolean
    /** कम से कम एक बार शब्द आए */
    val working: Boolean
    /** आख़िरी शब्द कब आए */
    val textAt: Long
    /** चल सकता है (बंद / ख़राब नहीं) */
    val usable: Boolean
    /** अभी तक की बात (पक्का + अंदाज़ा) */
    fun text(): String
    /** अभी का text फेंको (फ़ोन की अपनी आवाज़ थी) */
    fun discard()
    fun start()
    fun stop()
    /** DK ने जवाब शुरू किया: अभी की बात बंद करो और लौटा दो (दूसरी बार "") */
    fun closeUtterance(): String
    /** माइक का 16 kHz PCM16 टुकड़ा - कभी नहीं रुकता (माइक / Gemini Live पर असर नहीं) */
    fun send(pcm: ByteArray, real: Boolean = true)
    /** फ़ोन ने पकड़ा कि बोलने वाला चुप हुआ - पक्का text जल्दी */
    fun endOfSpeech()
}
