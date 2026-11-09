package com.dk.assistant

import android.content.Context
import android.media.AudioManager

/**
 * मालिक (09 नवंबर 2026): "DK ऐप / DK लाइव जुड़ा रहे तब भी -
 *  - मीडिया वॉल्यूम जितना रखा, उतना ही रहे: न बढ़े, न घटे.
 *  - कॉल का वॉल्यूम और आने वाली कॉल की रिंगटोन कम हो तो अपने-आप फुल; फुल हो तो वैसी ही; कभी कम नहीं.
 *  - DK को कोई बोले 'बढ़ाओ / घटाओ' तभी बदले."
 *
 * इसलिए:
 *  1. [raiseForCall] - कॉल बजते ही रिंग + कॉल की आवाज़ max (सिर्फ़ ऊपर; बाद में वापस कम नहीं करते).
 *     फ़ोन साइलेंट / वाइब्रेट पर हो तो रिंग नहीं छेड़ते (वह यूज़र ने खुद चुना है; बदलने को DND इजाज़त भी लगती).
 *  2. [keepMedia] - DK का कोई भी आवाज़-काम (कॉल पर accessibility आवाज़, कॉल-mode स्पीकर चालू/बंद) करने से
 *     पहले मीडिया वॉल्यूम याद, बाद में बदला मिला तो वापस वहीं. (कई फ़ोनों में accessibility आवाज़ मीडिया से जुड़ी है.)
 *  3. यूज़र ने अभी-अभी आवाज़ बदलने को बोला ([userAsked]) = कुछ वापस नहीं करते.
 */
object VolumeKeep {
    /** यूज़र के "आवाज़ बढ़ाओ/घटाओ" के बाद इतनी देर तक मीडिया को वापस नहीं करते */
    const val USER_WINDOW_MS = 5_000L

    @Volatile private var userAskedAt = 0L

    /** DeviceActions.volume (यूज़र का हुक्म) से बुलाया जाता है */
    fun markUserAsked(now: Long = System.currentTimeMillis()) { userAskedAt = now }
    fun userAsked(now: Long = System.currentTimeMillis()): Boolean = now - userAskedAt in 0 until USER_WINDOW_MS

    /** रिंग / कॉल की आवाज़ का नया स्तर: कम हो तो max, कभी कम नहीं. ring=true पर सिर्फ़ normal ringer mode में */
    fun upTarget(cur: Int, max: Int, allowed: Boolean = true): Int =
        if (!allowed || max <= 0 || cur >= max) cur else max

    /** मीडिया: DK के काम से बदल गया (और यूज़र ने नहीं कहा) = पुराना स्तर वापस; वरना null (कुछ नहीं) */
    fun mediaRestore(before: Int, now: Int, userAsked: Boolean): Int? =
        if (before < 0 || userAsked || before == now) null else before

    /** कॉल बज रही है: रिंगटोन + कॉल की आवाज़ कम हो तो फुल. कभी कम नहीं करता, बाद में वापस भी नहीं. */
    fun raiseForCall(c: Context) {
        val am = try { c.applicationContext.getSystemService(AudioManager::class.java) } catch (_: Exception) { null } ?: return
        keepMedia(am) {
            val normal = try { am.ringerMode == AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) { false }
            raise(am, AudioManager.STREAM_RING, normal, "रिंगटोन")
            raise(am, AudioManager.STREAM_VOICE_CALL, true, "कॉल की आवाज़")
        }
    }

    private fun raise(am: AudioManager, stream: Int, allowed: Boolean, name: String) {
        try {
            val cur = am.getStreamVolume(stream); val max = am.getStreamMaxVolume(stream)
            val t = upTarget(cur, max, allowed)
            if (t > cur) { am.setStreamVolume(stream, t, 0); DKLog.d("$name कम थी ($cur/$max) - फुल की") }
        } catch (e: SecurityException) { DKLog.d("$name फुल नहीं हुई (DND इजाज़त): ${e.message}") }
        catch (_: Exception) {}
    }

    /** [block] DK का कोई आवाज़-काम; उससे मीडिया वॉल्यूम बदला = वापस वहीं */
    inline fun <T> keepMedia(am: AudioManager?, block: () -> T): T {
        val before = try { am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1 } catch (_: Exception) { -1 }
        val r = block()
        if (am != null) restoreMedia(am, before)
        return r
    }

    fun restoreMedia(am: AudioManager, before: Int) {
        try {
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val back = mediaRestore(before, now, userAsked()) ?: return
            am.setStreamVolume(AudioManager.STREAM_MUSIC, back, 0)
            DKLog.d("मीडिया वॉल्यूम DK के काम से बदला था ($before -> $now) - वापस $back")
        } catch (_: Exception) {}
    }
}
