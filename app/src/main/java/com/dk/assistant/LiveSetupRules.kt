package com.dk.assistant

/**
 * Gemini Live setup के नियम - Google के docs से मिलाए (नवंबर 2026), JVM test में चलते हैं.
 * https://ai.google.dev/gemini-api/docs/live-guide
 *
 *  - "native audio models only support AUDIO response modality": Live से TEXT कभी नहीं माँगते.
 *    Kokoro / Gemini TTS जैसा अलग आवाज़ इंजन चुना हो = AUDIO + outputAudioTranscription, Live की आवाज़ नहीं बजती,
 *    उसकी लिखावट वह इंजन बोलता है (पहले TEXT माँगते थे -> setup मना -> Live जुड़ता ही नहीं था).
 *  - native audio मॉडल "don't support explicitly setting the language code" -> speechConfig.languageCode नहीं
 *    (हिंदी में बोलना system instruction से).
 *  - enableAffectiveDialog: 3.1 Flash Live नहीं लेता, 3.8-live के लिए docs साफ़ नहीं -> पहले setup में नहीं.
 */
object LiveSetupRules {
    const val MODALITY = "AUDIO"

    /** Live (native audio) मॉडल? - नाम में "live" / "native-audio" */
    fun isLiveAudioModel(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("live") || m.contains("native-audio")
    }

    fun sendLanguageCode(model: String): Boolean = !isLiveAudioModel(model)

    @Suppress("UNUSED_PARAMETER")
    fun sendAffective(model: String): Boolean = false

    /** Live से TEXT जवाब माँगना - कभी नहीं (बँटवारे में भी AUDIO की लिखावट) */
    @Suppress("UNUSED_PARAMETER")
    fun askText(model: String, split: Boolean): Boolean = false
}
