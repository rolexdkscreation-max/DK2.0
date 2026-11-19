package com.dk.assistant.voice.st3

import android.content.Context
import com.dk.assistant.plus.models.ModelManager

/**
 * 19 नवंबर 2026: नई आवाज़ (Supertonic 3) की पसंद - कौन-सी आवाज़ (F1-F5), रफ़्तार, कदम. अलग prefs "dk_st3"
 * (पुराने हटाए गए "dk_supertonic*" से नाम अलग, ताकि पुरानी सफ़ाई इसे न मिटाए). कोई पुरानी setting नहीं बदलती.
 */
object St3Store {
    private const val P = "dk_st3"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun voice(c: Context): String = St3Config.cleanVoice(p(c).getString("voice", St3Config.DEFAULT_VOICE))
    fun setVoice(c: Context, id: String) { p(c).edit().putString("voice", St3Config.cleanVoice(id)).apply() }
    fun speed(c: Context): Float = St3Config.cleanSpeed(p(c).getFloat("speed", St3Config.SPEED_DEF))
    fun setSpeed(c: Context, s: Float) { p(c).edit().putFloat("speed", St3Config.cleanSpeed(s)).apply() }
    fun steps(c: Context): Int = St3Config.cleanSteps(p(c).getInt("steps", St3Config.STEPS_DEF))
    fun setSteps(c: Context, n: Int) { p(c).edit().putInt("steps", St3Config.cleanSteps(n)).apply() }

    /** मुख्य मॉडल (चारों + दो json) फ़ोन में पूरे */
    fun coreReady(c: Context): Boolean = St3Config.CORE.all { ModelManager.ready(c, it) }
    fun voiceReady(c: Context, id: String): Boolean = ModelManager.ready(c, St3Config.voice(id).asset)
    /** चुनी हुई आवाज़ डाउनलोड न हो तो जो पहली डाउनलोड है वह; कोई नहीं तो null */
    fun usableVoice(c: Context): String? = voice(c).takeIf { voiceReady(c, it) } ?: St3Config.VOICES.firstOrNull { voiceReady(c, it.id) }?.id
    fun ready(c: Context): Boolean = coreReady(c) && usableVoice(c) != null

    fun files(c: Context) = St3Synth.Files(
        ModelManager.file(c, St3Config.DP), ModelManager.file(c, St3Config.TEXT_ENC), ModelManager.file(c, St3Config.VECTOR),
        ModelManager.file(c, St3Config.VOCODER), ModelManager.file(c, St3Config.CFG), ModelManager.file(c, St3Config.INDEXER))
}
