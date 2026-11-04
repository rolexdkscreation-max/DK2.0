package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.voice.VoiceCmdMatch.Cmd
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * नवंबर 2026 (मालिक): बिना इंटरनेट / API / Gemini Live वाली आवाज़-कमांड - Android हिस्सा.
 * सिखाए नमूने `files/voice/cmd_<id>.json` (सिर्फ़ अंक - आवाज़ सेव नहीं). मिलान [VoiceCmdMatch].
 *
 * तीन जगह से माइक की आवाज़ आती है (एक ही [match]):
 *  1. "Hey DK" वाला सुनना ([HeyDk.listen]) - Live बंद,
 *  2. पुराना सुनने वाला loop (`hear()`) - "Hey DK" सिखाया न हो,
 *  3. Gemini Live का माइक (`micLoop`) - Live चालू: मिलान होते ही काम फ़ोन पर, Live का जवाब बंद.
 *
 * मालिक: अनलॉक (और लॉक) सिर्फ़ उसकी आवाज़ पर - आवाज़-पहचान (CAM++) ज़रूरी, न हो तो कमांड नहीं.
 * "किधर हो" / "मिल गया" कोई भी बोले.
 */
object VoiceCmds {
    private fun file(c: Context, cmd: Cmd) = File(c.filesDir, "voice/cmd_${cmd.id}.json")
    private val cache = ConcurrentHashMap<Cmd, Pair<Long, VoiceCmdMatch.Model>>()

    fun model(c: Context, cmd: Cmd): VoiceCmdMatch.Model? {
        val f = file(c, cmd)
        if (!f.exists()) return null
        val lm = f.lastModified()
        cache[cmd]?.takeIf { it.first == lm }?.let { return it.second }
        return VoiceCmdMatch.Model.fromJson(try { f.readText() } catch (_: Exception) { null })?.also { cache[cmd] = lm to it }
    }

    fun trained(c: Context, cmd: Cmd) = file(c, cmd).exists()
    fun on(c: Context, cmd: Cmd) = trained(c, cmd) && Prefs.voiceCmdOn(c, cmd.id)
    fun anyOn(c: Context) = Cmd.values().any { on(c, it) }
    fun setOn(c: Context, cmd: Cmd, on: Boolean) = Prefs.edit(c) { putBoolean("voice_cmd_on_${cmd.id}", on) }

    /** null = ठीक; वरना हिंदी कारण. दूसरा = चेतावनी (सेव हुआ, पर दूसरी कमांड से मिलता-जुलता) */
    fun save(c: Context, cmd: Cmd, takes: List<ByteArray>): Pair<String?, String?> {
        val feats = takes.map { HeyDk.featuresOf(it) }.filter { it.size >= VoiceCmdMatch.MIN_FRAMES / 2 }
        if (feats.size < 3) return "आवाज़ साफ़ नहीं आई - शांत जगह पर दोबारा सिखाइए" to null
        val m = VoiceCmdMatch.Model.build(feats) ?: return "नमूने आपस में नहीं मिले - हर बार एक ही वाक्य, एक ही तरह बोलिए" to null
        file(c, cmd).parentFile?.mkdirs()
        file(c, cmd).writeText(m.toJson())
        cache.remove(cmd)
        val prints = if (OwnerVoice.supported && OwnerVoice.hasModel(c)) takes.mapNotNull { OwnerVoice.embed(c, it) } else emptyList()
        val print = VoicePrint.mean(prints)
        Prefs.edit(c) {
            if (print != null) putString("voice_cmd_print_${cmd.id}", VoicePrint.encode(print)) else remove("voice_cmd_print_${cmd.id}")
            putBoolean("voice_cmd_on_${cmd.id}", true)
        }
        val clash = Cmd.values().filter { it != cmd }.firstOrNull { o -> model(c, o)?.let { VoiceCmdMatch.confusable(m, it) } == true }
        DKLog.d("आवाज़-कमांड सीखी: ${cmd.id}, ${feats.size} नमूने, सीमा ${"%.2f".format(m.base)}, आवाज़-पहचान ${if (print != null) "हाँ" else "नहीं"}" +
            (clash?.let { ", ${it.id} से मिलती-जुलती" } ?: ""))
        val warn = clash?.let { "\"${cmd.hi}\" और \"${it.hi}\" के वाक्य बहुत मिलते-जुलते हैं - DK पक्का न हो तो कुछ नहीं करेगा। अलग वाक्य रखें (जैसे \"फोन खोलो\" / \"फोन बंद करो\")" }
        return null to warn
    }

    fun forget(c: Context, cmd: Cmd) {
        file(c, cmd).delete(); cache.remove(cmd)
        Prefs.edit(c) { remove("voice_cmd_print_${cmd.id}") }
    }

    fun status(c: Context, cmd: Cmd): String = when {
        !trained(c, cmd) -> "अभी नहीं सिखाया"
        !Prefs.voiceCmdOn(c, cmd.id) -> "सिखाया है • बंद"
        cmd.ownerOnly && Prefs.voiceCmdPrint(c, cmd.id) == null && Prefs.heyDkPrint(c) == null -> "⚠ सिखाया है, पर आवाज़-पहचान नहीं - दोबारा सिखाइए (मॉडल डाउनलोड होगा)"
        else -> "✓ सिखाया है • चालू" + if (cmd.ownerOnly) " • सिर्फ़ आपकी आवाज़" else " • कोई भी बोले"
    }

    class Hit(val cmd: Cmd, val ratio: Float, val owner: Float?)

    /** एक ही बात दो रास्तों (Hey DK / Live) से दो बार न चले */
    private val lastFired = ConcurrentHashMap<Cmd, Long>()
    fun claim(cmd: Cmd): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastFired[cmd] ?: 0L
        if (now - prev < 2_500) return false
        lastFired[cmd] = now
        return true
    }

    /**
     * पूरी बोली बात (16 kHz PCM) किस कमांड की. फ़ोन पर, ~20-60 ms. बहुत छोटी/लंबी या पक्का न हो = null.
     * मालिक वाली कमांड पर आवाज़-पहचान: अनलॉक पर कड़ी सीमा ([VoicePrint.TH_STRICT]).
     */
    fun match(c: Context, pcm: ByteArray): Hit? {
        val ms = pcm.size / 2 * 1000 / WakeMatch.RATE
        if (ms < VoiceCmdMatch.MIN_MS || ms > VoiceCmdMatch.MAX_MS + 1_500) return null
        val models = Cmd.values().filter { on(c, it) }.mapNotNull { k -> model(c, k)?.let { k to it } }.toMap()
        if (models.isEmpty()) return null
        val f = try { HeyDk.featuresOf(pcm) } catch (_: Exception) { return null }
        if (f.size < VoiceCmdMatch.MIN_FRAMES / 2) return null
        val p = VoiceCmdMatch.pick(models, f, Prefs.heyDkSense(c)) ?: return null
        var owner: Float? = null
        if (p.cmd.ownerOnly) {
            val print = Prefs.voiceCmdPrint(c, p.cmd.id) ?: Prefs.heyDkPrint(c)
            if (!OwnerVoice.supported || !OwnerVoice.hasModel(c) || print == null) {
                DKLog.d("आवाज़-कमांड \"${p.cmd.hi}\" सुनी, पर आवाज़-पहचान नहीं (मॉडल/नमूना) - सुरक्षा के लिए नहीं की")
                return null
            }
            val e = OwnerVoice.embed(c, pcm) ?: return null
            owner = VoicePrint.cosine(print, e)
            val th = if (p.cmd == Cmd.UNLOCK) VoicePrint.TH_STRICT else VoicePrint.threshold(Prefs.ownerVoiceStrict(c))
            if (owner < th) {
                DKLog.d("आवाज़-कमांड \"${p.cmd.hi}\" जैसा सुना, पर आवाज़ आपकी नहीं (${"%.2f".format(owner)} < ${"%.2f".format(th)}) - नहीं की")
                return null
            }
        }
        DKLog.d("आवाज़-कमांड (बिना इंटरनेट): ${p.cmd.hi} • मिलान ${"%.2f".format(p.ratio)}${owner?.let { " • आवाज़ %.2f".format(it) } ?: ""} • ${ms} ms")
        return Hit(p.cmd, p.ratio, owner)
    }
}
