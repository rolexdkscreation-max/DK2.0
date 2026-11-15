package com.dk.assistant

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * 15 नवंबर 2026 (मालिक): "जब कॉल आता है और मैं DK को बोलता हूँ तो कॉल नहीं उठा पाता. DK कॉल उठाए, जैसे बोलूँ तो.
 * और अंदर एक सेटिंग रखो - DK की कॉलिंग के लिए, जिसे मैं कस्टमाइज़ कर सकूँ: कौन-सी रिंग बजे, कितनी तेज़ बजे."
 *
 * जड़: घंटी बजते ही रिंगटोन फुल ([VolumeKeep.raiseForCall]) और "घंटी धीमी" वाला [CallManager.quietRinger] खाली था -
 * पूरी आवाज़ की रिंग में माइक को "DK उठा लो" सुनाई नहीं देता था. अब:
 *  - [ringMode]: घंटी कितनी तेज़ - FULL (पहले जैसा, कम हो तो फुल) / KEEP (जितनी रखी) / LEVEL (तय %)
 *  - [listenQuiet]: DK जब तक जवाब सुन रही है, घंटी [listenPct]% तक धीमी (सिर्फ़ नीचे) - ताकि आपकी आवाज़ सुनाई दे
 *  - कॉल ख़त्म = रिंग की पुरानी आवाज़ वापस ([restore])
 *  - [listenMs]: घंटी के समय DK कितनी देर "उठा लो / काट दो" सुने
 * Pure हिस्से JVM test में.
 */
object CallTune {
    const val FULL = "full"
    const val KEEP = "keep"
    const val LEVEL = "level"

    const val K_MODE = "call_ring_mode"
    const val K_PCT = "call_ring_pct"
    const val K_QUIET = "call_listen_quiet"
    const val K_QPCT = "call_listen_pct"
    const val K_SEC = "call_listen_sec"
    /** dk_ring prefs में: घंटी से पहले रिंग की आवाज़ (-1 = कुछ नहीं बदला) */
    const val K_SAVED = "saved_ring"

    const val DEF_PCT = 70
    const val DEF_QPCT = 25
    const val DEF_SEC = 30
    val SECONDS = listOf(15, 30, 45, 60)

    fun ringMode(c: Context) = Prefs.str(c, K_MODE, FULL).takeIf { it in listOf(FULL, KEEP, LEVEL) } ?: FULL
    fun ringPct(c: Context) = num(c, K_PCT, DEF_PCT).coerceIn(0, 100)
    fun listenQuiet(c: Context) = Prefs.bool(c, K_QUIET, true)
    fun listenPct(c: Context) = num(c, K_QPCT, DEF_QPCT).coerceIn(0, 100)
    fun listenMs(c: Context) = secOk(num(c, K_SEC, DEF_SEC)) * 1000L

    private fun num(c: Context, k: String, def: Int) = Prefs.str(c, k, def.toString()).toIntOrNull() ?: def
    fun setNum(c: Context, k: String, v: Int) = Prefs.edit(c) { putString(k, v.toString()) }

    // ------------------------------------------------------------------ pure

    fun secOk(s: Int) = s.coerceIn(SECONDS.first(), SECONDS.last())

    /** % -> फ़ोन का स्तर. कम से कम 1 (0 = कई फ़ोन वाइब्रेट/साइलेंट कर देते, उसके लिए DND इजाज़त भी लगती) */
    fun levelFor(pct: Int, max: Int): Int = if (max <= 0) 0 else (max * pct.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(1, max)

    /** घंटी शुरू: रिंग का नया स्तर. फ़ोन साइलेंट/वाइब्रेट (normal=false) = कभी नहीं छेड़ते */
    fun ringTarget(mode: String, cur: Int, max: Int, pct: Int, normal: Boolean): Int = when {
        !normal || max <= 0 -> cur
        mode == LEVEL -> levelFor(pct, max)
        mode == KEEP -> cur
        else -> VolumeKeep.upTarget(cur, max, true)
    }

    /** DK सुन रही है: घंटी सिर्फ़ नीचे (पहले से धीमी हो तो वैसी ही) */
    fun listenTarget(cur: Int, max: Int, pct: Int): Int = if (max <= 0) cur else minOf(cur, levelFor(pct, max))

    /** Settings की जाँच: बोलकर कॉल उठाने के लिए क्या-क्या चालू है */
    fun checks(announce: Boolean, phoneState: Boolean, answer: Boolean, a11y: Boolean): String {
        val miss = buildList {
            if (!phoneState) add("फ़ोन की स्थिति (READ_PHONE_STATE) परमिशन")
            if (!answer) add("कॉल उठाने (ANSWER_PHONE_CALLS) की परमिशन")
            if (!a11y) add("DK Accessibility")
        }
        val head = if (miss.isEmpty()) "✓ बोलकर कॉल उठाना तैयार है" else "बोलकर कॉल उठाने के लिए चालू करें: " + miss.joinToString(", ")
        return head + if (!announce) "\n(कॉल का एलान बंद है — DK लाइव फिर भी \"उठा लो\" सुनेगी)" else ""
    }

    /** कॉल ख़त्म: सहेजा स्तर वापस (कुछ नहीं सहेजा / वही है = null) */
    fun restoreTo(saved: Int, now: Int): Int? = if (saved < 0 || saved == now) null else saved

    // ------------------------------------------------------------------ फ़ोन पर

    private fun am(c: Context) = try { c.applicationContext.getSystemService(AudioManager::class.java) } catch (_: Exception) { null }
    private fun normal(am: AudioManager) = try { am.ringerMode == AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) { false }

    private fun setRing(c: Context, store: SharedPreferences, am: AudioManager, t: Int, why: String) {
        try {
            val cur = am.getStreamVolume(AudioManager.STREAM_RING)
            if (t == cur) return
            if (store.getInt(K_SAVED, -1) < 0) store.edit().putInt(K_SAVED, cur).apply()
            VolumeKeep.keepMedia(am) { am.setStreamVolume(AudioManager.STREAM_RING, t, 0) }
            DKLog.d("घंटी: $why ($cur -> $t / ${am.getStreamMaxVolume(AudioManager.STREAM_RING)})")
        } catch (e: SecurityException) { DKLog.d("घंटी की आवाज़ नहीं बदली (DND इजाज़त): ${e.message}") }
        catch (_: Exception) {}
    }

    /** घंटी बजते ही: चुनी हुई तेज़ी (FULL = पुराना VolumeKeep.raiseForCall, वहीं से बुलाया जाता है) */
    fun applyRing(c: Context, store: SharedPreferences) {
        val am = am(c) ?: return
        val mode = ringMode(c)
        if (mode == FULL) return
        val max = try { am.getStreamMaxVolume(AudioManager.STREAM_RING) } catch (_: Exception) { 0 }
        val cur = try { am.getStreamVolume(AudioManager.STREAM_RING) } catch (_: Exception) { return }
        setRing(c, store, am, ringTarget(mode, cur, max, ringPct(c), normal(am)), "कॉल की सेटिंग")
        // कॉल की (बात वाली) आवाज़ पहले जैसी: कम हो तो फुल
        try {
            val vc = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL); val vm = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val t = VolumeKeep.upTarget(vc, vm, true); if (t > vc) VolumeKeep.keepMedia(am) { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, t, 0) }
        } catch (_: Exception) {}
    }

    /** DK जवाब सुन रही है = घंटी धीमी (force = यूज़र ने "घंटी धीमी करो" कहा: सबसे धीमी) */
    fun quiet(c: Context, store: SharedPreferences, force: Boolean = false) {
        if (!force && !listenQuiet(c)) return
        val am = am(c) ?: return
        if (!normal(am)) return
        val max = try { am.getStreamMaxVolume(AudioManager.STREAM_RING) } catch (_: Exception) { 0 }
        val cur = try { am.getStreamVolume(AudioManager.STREAM_RING) } catch (_: Exception) { return }
        setRing(c, store, am, listenTarget(cur, max, if (force) 0 else listenPct(c)), if (force) "धीमी (आपके कहने पर)" else "DK सुन रही है, धीमी")
    }

    /** कॉल ख़त्म = घंटी की पुरानी आवाज़ */
    fun restore(c: Context, store: SharedPreferences) {
        val saved = store.getInt(K_SAVED, -1)
        store.edit().putInt(K_SAVED, -1).apply()
        val am = am(c) ?: return
        val now = try { am.getStreamVolume(AudioManager.STREAM_RING) } catch (_: Exception) { return }
        val t = restoreTo(saved, now) ?: return
        try { VolumeKeep.keepMedia(am) { am.setStreamVolume(AudioManager.STREAM_RING, t, 0) }; DKLog.d("घंटी: पुरानी आवाज़ वापस ($now -> $t)") }
        catch (_: Exception) {}
    }
}
