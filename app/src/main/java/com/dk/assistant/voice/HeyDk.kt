package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.voice.engine.PreviewPlayer
import com.dk.assistant.voice.engine.VoiceEngines
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v3.81: "Hey DK" के साथ के हिस्से - अब सिर्फ़ "हाँ सर" (ack) आवाज़ें और आवाज़-कमांड के अंक (16 नवंबर 2026 से पहचान [com.dk.assistant.wake.WakeWord] में).
 *
 *  - माइक की आवाज़ कहीं नहीं जाती: बोलना शुरू -> छोटी बात -> [WakeMatch] (शब्द) + [VoiceGuardian] (आवाज़) -> नहीं मिला = भूल जाओ.
 *  - मिला = तुरंत "हाँ सर" (पहले से बना रखा, इंटरनेट का इंतज़ार नहीं) -> Gemini Live -> चुप्पी पर वापस सो जाओ.
 *  - "Hey DK, WhatsApp खोलो" एक साँस में = बिना "हाँ सर" के सीधे वही काम.
 */
object HeyDk {

    // 16 नवंबर 2026 (मालिक): पुराना "Hey DK" पहचानना (खुद सिखाने वाला DTW + CAM++) हटाया - नया वेक वर्ड
    // [com.dk.assistant.wake.WakeWord] ("हे डीके / हेलो डीके", openWakeWord). backup: backup/dk-old-heydk-wake.
    // यहाँ सिर्फ़ वह बचा जो दूसरी सुविधाएँ इस्तेमाल करती हैं: आवाज़-कमांड के अंक और "हाँ सर" वाली आवाज़ें.

    /** रिकॉर्ड में से सिर्फ़ बोला हुआ हिस्सा कितने फ़्रेम (10 ms) - आवाज़-कमांड सिखाने में */
    fun spokenFrames(pcm: ByteArray): Int = featuresOf(pcm).size

    fun featuresOf(pcm: ByteArray): Array<FloatArray> = WakeMatch.features(SpeakerFbank.pcm16(pcm))

    // ------------------------------------------------------------------ "हाँ सर" - तुरंत

    @Volatile private var lastAck = ""

    fun acks(c: Context) = WakeMatch.acksFor(Prefs.heyDkAcks(c), Prefs.userName(c))

    fun nextAck(c: Context): String = WakeMatch.nextAck(acks(c), lastAck).also { lastAck = it }

    /** पहले से बनाकर फ़ोन में रखी आवाज़ें: "हाँ सर" वाले + "मैं इधर हूँ बॉस" (फ़ोन ढूँढना - तब इंटरनेट न हो तो भी) */
    fun clipTexts(c: Context): List<String> = (acks(c) + com.dk.assistant.FindPhone.SAY + com.dk.assistant.GuardRules.SAY).distinct()

    /** बनी हुई आवाज़ (24 kHz PCM) - नहीं बनी = null */
    fun clipPcm(c: Context, text: String): ByteArray? = ackPick(c, text)?.let { try { it.readBytes() } catch (_: Exception) { null } }

    /** जो इंजन अकेले बोल सकता है (Live की अपनी आवाज़ अकेले नहीं बोलती -> Gemini TTS) */
    private fun ackEngine(c: Context) = listOfNotNull(VoiceEngines.active(c), VoiceEngines.byId(VoiceEngines.GEMINI_TTS))
        .firstOrNull { it.id != VoiceEngines.LIVE_NATIVE && it.notReady(c) == null }

    private fun ackFile(c: Context, engineId: String, voice: String, text: String): File {
        val h = java.security.MessageDigest.getInstance("MD5").digest("$engineId|$voice|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(c.filesDir, "voice/ack"), "$h.pcm")
    }

    /** सब "हाँ सर" पहले से बना लो (एक बार, इंटरनेट से; फिर फ़ोन में). IO thread पर बुलाओ */
    fun prepareAcks(c: Context) {
        val e = ackEngine(c) ?: return
        val voice = e.voice(c)
        // DKI-9DC74D (नवंबर 2026): जड़ - Worker (dk-brain) में APP_TOKEN secret नहीं = हर power बटन / Hey DK पर Worker फिर
        // बुलाया जाता, हर बार 500 और वही गलती (29 बार). अब: (1) Live आवाज़ चालू हो और Live वाला ack बना हो = Worker की ज़रूरत
        // ही नहीं; (2) Worker नाकाम = 10 मिनट तक दोबारा नहीं (टिंग/Live वाली आवाज़ चलती रहे).
        val now = System.currentTimeMillis()
        if (!LiveAckRules.retryDue(ackFailAt, now)) return
        // नवंबर 2026: एक समय पर एक ही - जवाब आने तक दबाया हर बटन नई request न भेजे (एक ही फ़ाइल पर दो लिखने वाले भी नहीं)
        if (!LiveAckRules.tryBegin(ackBusy)) return
        try { makeAcks(c, e, voice) } finally { ackBusy.set(false) }
    }

    private fun makeAcks(c: Context, e: com.dk.assistant.voice.engine.VoiceEngine, voice: String) {
        val liveOn = VoiceEngines.activeId(c) == VoiceEngines.LIVE_NATIVE
        for (t in clipTexts(c)) {
            val f = ackFile(c, e.id, voice, t)
            if (f.exists() && f.length() > 2000) continue
            if (liveOn && hasLiveAck(c, t)) continue
            try {
                val out = ByteArrayOutputStream()
                e.speak(c, t, voice) { out.write(it) }
                if (out.size() > 2000) { f.parentFile?.mkdirs(); f.writeBytes(out.toByteArray()); ackFailAt = 0L }
                else {
                    // 200 पर भी खाली/कटी आवाज़ = नाकामी (वरना हर बटन पर फिर माँगते)
                    ackFailAt = System.currentTimeMillis()
                    DKLog.d("Hey DK जवाब की आवाज़ खाली आई - ${ACK_RETRY_MS / 60_000} मिनट तक दोबारा नहीं")
                    return
                }
            } catch (ex: Exception) {
                ackFailAt = System.currentTimeMillis()
                DKLog.d("Hey DK जवाब की आवाज़ नहीं बनी: ${ex.message} - ${ACK_RETRY_MS / 60_000} मिनट तक दोबारा नहीं")
                return
            }
        }
    }

    /** Worker/TTS नाकाम होने के बाद इतनी देर दोबारा नहीं */
    const val ACK_RETRY_MS = LiveAckRules.RETRY_MS
    @Volatile private var ackFailAt = 0L
    private val ackBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** नवंबर 2026: यह "हाँ सर" फ़ोन में पहले से बना है? (power बटन पर तुरंत - बिना इंटरनेट) */
    fun hasAck(c: Context, text: String): Boolean = ackPick(c, text) != null

    /** मालिक (नवंबर 2026): DK की अपनी Gemini Live आवाज़ में बना "Yes Boss" ([LiveAckMaker]) - voice बदली = नई फ़ाइल */
    fun liveAckFile(c: Context, voice: String, text: String): File = ackFile(c, VoiceEngines.LIVE_NATIVE, voice, text)

    fun hasLiveAck(c: Context, text: String): Boolean = liveAckFile(c, Prefs.geminiVoice(c), text).let { it.exists() && it.length() > 1000 }

    /** कौन सी बनी आवाज़ बजे: Live आवाज़ चालू (डिफ़ॉल्ट) = Live वाली पहले, फिर पुराना इंजन वाला. कोई नहीं = null */
    private fun ackPick(c: Context, text: String): File? {
        val live = liveAckFile(c, Prefs.geminiVoice(c), text).takeIf { it.exists() && it.length() > 1000 }
        val e = ackEngine(c)
        val eng = e?.let { ackFile(c, it.id, it.voice(c), text) }?.takeIf { it.exists() && it.length() > 2000 }
        return if (VoiceEngines.activeId(c) == VoiceEngines.LIVE_NATIVE) live ?: eng else eng ?: live
    }

    /** पहले से बनी आवाज़ बजाओ (रुककर, ~1 s). @return false = बनी नहीं थी (तब आम तरीके से बोलो) */
    fun playAck(c: Context, text: String): Boolean {
        val f = ackPick(c, text) ?: return false
        val pcm = try { f.readBytes() } catch (_: Exception) { return false }
        return PreviewPlayer.play(c) { out -> out.write(pcm) } >= 0
    }
}
