package com.dk.assistant.voice

import java.util.concurrent.Executors

/**
 * 16 नवंबर 2026 (मालिक: "अपनी आवाज़ पहचान के दो इंजन - पुराना हटाओ"): पुराना CAM++ (OwnerVoice वाला) इंजन हटा.
 * उसकी दो साझा चीज़ें यहाँ आ गईं - आवाज़ मिलान का अलग thread और "यह फ़ोन ONNX चला सकता है" वाली जाँच.
 * अब फ़ोन पर आवाज़-पहचान का एक ही इंजन: [VoiceGuardian] (RedimNet).
 */
object VoiceExec {
    /** मिलान mic thread पर नहीं - अलग एक thread (एक बार में एक) */
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-owner-voice").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 } }

    /** पहचान वाली लाइब्रेरी सिर्फ़ 64-bit ARM (arm64) फ़ोनों के लिए APK में है (APK छोटा रखने को) */
    val supported: Boolean by lazy {
        try { android.os.Process.is64Bit() && android.os.Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") } catch (_: Throwable) { false }
    }
}
