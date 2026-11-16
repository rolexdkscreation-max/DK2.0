package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.Prefs

/**
 * 16 नवंबर 2026: "दूर की आवाज़ अनसुनी" वाला स्तर (सिर्फ़ आवाज़ की ज़ोर, पहचान नहीं).
 * पहले यह पुराने CAM++ इंजन (OwnerVoice, अब हटाया) के अंदर था - काम वैसा ही, बस अलग जगह.
 */
object OwnerLevel {
    /** पहचानी गई मालिक की बात का स्तर - दूर/पास का अंदाज़ा धीरे-धीरे सुधरे */
    fun learnLevel(c: Context, utter: Double) {
        val enrolled = Prefs.ownerLevel(c)
        val now = VoicePrint.adaptLevel(enrolled, Prefs.ownerLevelNow(c), utter)
        if (now > 0) Prefs.edit(c) { putFloat("owner_level_now", now.toFloat()) }
    }

    /** दूर की आवाज़ की सीमा (0 = बंद): Bluetooth headset पर बंद (वहाँ माइक मुँह के पास, स्तर अलग) */
    fun farFloor(c: Context, bluetooth: Boolean): Double {
        if (bluetooth || !Prefs.ownerFarOn(c)) return 0.0
        val lv = Prefs.ownerLevelNow(c).takeIf { it > 0 } ?: Prefs.ownerLevel(c)
        return VoicePrint.farFloor(lv, Prefs.ownerVoiceStrict(c))
    }

    /**
     * पुराने CAM++ इंजन का बचा सामान एक बार मिटाओ: ~28 MB मॉडल फ़ाइल और उसकी आवाज़-पहचान (prefs).
     * आवाज़-कमांड के पुराने CAM++ नमूने भी - वे नए इंजन से मेल नहीं खाते (अब [VoiceGuardian] की सीखी आवाज़ से मिलान).
     */
    fun cleanupOldEngine(c: Context) {
        try {
            val f = java.io.File(java.io.File(c.filesDir, "voice"), "campplus_zh_en.onnx")
            if (f.exists()) f.delete()
            java.io.File(java.io.File(c.filesDir, "voice"), "model.part").delete()
            if (Prefs.ownerPrint(c) != null || VoiceCmdMatch.Cmd.values().any { Prefs.voiceCmdPrint(c, it.id) != null }) {
                Prefs.edit(c) { remove("owner_print"); VoiceCmdMatch.Cmd.values().forEach { remove("voice_cmd_print_${it.id}") } }
            }
        } catch (e: Exception) { com.dk.assistant.DKLog.d("पुराना आवाज़ इंजन साफ़ नहीं हुआ (${e.javaClass.simpleName})") }
    }
}
