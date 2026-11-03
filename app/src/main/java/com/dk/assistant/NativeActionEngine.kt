package com.dk.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): DK का "Native Local Command Engine" - फ़ोन के सीधे काम एक ही जगह से.
 *
 * रास्ता (कोई नया दोहरा handler नहीं - पुराने वही रहते हैं):
 *  1. पहचान = [match] -> [LocalRouter.route] (उसके अंदर: OfflineCommands -> NativeCommands -> QuickCommands -> कॉल/खोज...)
 *     सब फ़ोन पर, बिना Gemini / क्लाउड / इंटरनेट. पक्का न हो (कई ऐप / कई कॉन्टैक्ट) = "ASK:" - user से पूछो.
 *  2. चलाना = [exec] -> [DKAccessibilityService.perform] (वही पुराना executor) + कितने ms लगे का हिसाब.
 *  3. नतीजा = वही पुराना LiveFast.quickResult -> DK Live सच्ची बात बोलता है.
 *
 * यहाँ सिर्फ़ वो नए काम जो पहले थे ही नहीं: ब्राइटनेस, ऑटो रोटेट, डू नॉट डिस्टर्ब.
 * Android जहाँ ऐप को सीधे बदलने नहीं देता (या इजाज़त नहीं) - वहाँ उसका सिस्टम पेज खोलकर साफ़ बताना, चुप कभी नहीं.
 */
object NativeActionEngine {
    val TYPES = NativeCommands.TYPES

    /** पूरी बात फ़ोन पर हो सकती है? (हाँ = काम, नहीं = null -> AI) */
    fun match(raw: String, wakeWords: List<String>, fg: String = ""): QuickCommands.Quick? = LocalRouter.route(raw, wakeWords, fg)

    /** पुराने executor से चलाओ + देरी का हिसाब (सेटिंग्स → देरी का हिसाब, और DKLog) */
    suspend fun exec(svc: DKAccessibilityService, q: QuickCommands.Quick, via: String): String {
        val t0 = System.nanoTime()
        val r = svc.perform(q.action)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val type = q.action.optString("type")
        val run = NativeCommands.record(type, ms, LiveFast.ok(type, r), via)
        DKLog.d("सीधा काम (बिना AI): " + NativeCommands.line(run))
        return r
    }

    // ------------------------------------------------------------------ नए काम

    fun run(ctx: Context, a: JSONObject): String = when (a.optString("type")) {
        "brightness" -> brightness(ctx, a.optString("direction", "up"), a.optInt("level", -1), a.optBoolean("on", true))
        "rotation" -> rotation(ctx, a.optBoolean("on", true))
        "dnd" -> dnd(ctx, a.optBoolean("on", true))
        else -> "ERROR: unknown action ${a.optString("type")}"
    }

    private fun open(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** "सिस्टम सेटिंग बदलें" की इजाज़त नहीं -> उसका पेज खोलो और साफ़ बताओ */
    private fun askWrite(ctx: Context, what: String): String {
        val opened = open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))) ||
            open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
        return "ERROR_PERM: $what बदलने के लिए DK को \"सिस्टम सेटिंग बदलें\" की इजाज़त चाहिए। " +
            (if (opened) "वह पेज खोल दिया है - DK को Allow कर दीजिए, फिर दोबारा बोलिए।" else "सेटिंग्स → ऐप्स → DK → सिस्टम सेटिंग बदलें में Allow कीजिए।")
    }

    private const val MAX = 255

    private fun brightness(ctx: Context, dir: String, level: Int, on: Boolean): String {
        if (!Settings.System.canWrite(ctx)) return askWrite(ctx, "ब्राइटनेस")
        val cr = ctx.contentResolver
        return try {
            if (dir == "auto") {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                return "SAID: " + if (on) "ऑटो ब्राइटनेस चालू कर दी।" else "ऑटो ब्राइटनेस बंद कर दी।"
            }
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, MAX / 2)
            val step = MAX / 4
            val v = when (dir) {
                "set" -> (level.coerceIn(0, 100) * MAX + 50) / 100
                "max" -> MAX
                "min" -> 1
                "down" -> cur - step
                else -> cur + step
            }.coerceIn(1, MAX)
            // हाथ वाला मोड, नहीं तो ऑटो ब्राइटनेस तुरंत वापस बदल देती
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            "SAID: ब्राइटनेस ${(v * 100 + MAX / 2) / MAX} प्रतिशत कर दी।"
        } catch (e: Exception) {
            open(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "ERROR_PERM: ब्राइटनेस सीधे नहीं बदल पाया (${e.message?.take(60)}), डिस्प्ले का पेज खोल दिया।"
        }
    }

    private fun rotation(ctx: Context, on: Boolean): String {
        if (!Settings.System.canWrite(ctx)) return askWrite(ctx, "ऑटो रोटेट")
        return try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            "SAID: " + if (on) "ऑटो रोटेट चालू कर दिया।" else "ऑटो रोटेट बंद कर दिया।"
        } catch (e: Exception) {
            open(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "ERROR_PERM: ऑटो रोटेट सीधे नहीं बदल पाया, डिस्प्ले का पेज खोल दिया।"
        }
    }

    private fun dnd(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return "ERROR: notification service नहीं मिली"
        if (!nm.isNotificationPolicyAccessGranted) {
            val opened = open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "ERROR_PERM: डू नॉट डिस्टर्ब बदलने के लिए DK को \"Do Not Disturb access\" चाहिए। " +
                (if (opened) "वह पेज खोल दिया है - DK को चालू कर दीजिए, फिर दोबारा बोलिए।" else "सेटिंग्स में DK को Do Not Disturb access दीजिए।")
        }
        return try {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            "SAID: " + if (on) "डू नॉट डिस्टर्ब चालू कर दिया।" else "डू नॉट डिस्टर्ब बंद कर दिया।"
        } catch (e: Exception) {
            open(ctx, Intent("android.settings.ZEN_MODE_SETTINGS"))
            "ERROR_PERM: डू नॉट डिस्टर्ब सीधे नहीं बदल पाया, उसका पेज खोल दिया।"
        }
    }

    /** Android 8 (SDK < 28) पर accessibility से लॉक नहीं होता - चुप नहीं, साफ़ बात */
    fun lockOld(): String = "ERROR_OLD_ANDROID: इस फ़ोन (Android ${Build.VERSION.RELEASE}) में DK स्क्रीन लॉक नहीं कर सकता - Android 9 या नया चाहिए। पावर बटन दबा दीजिए।"
}
