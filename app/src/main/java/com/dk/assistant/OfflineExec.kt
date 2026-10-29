package com.dk.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v4.1: [OfflineCommands] के कामों को चलाने वाला हिस्सा (कोई API नहीं).
 * नतीजा "SAID: ..." = बोलने लायक छोटा हिंदी वाक्य (LocalRouter.userText). परमिशन नहीं = "ERROR_PERM: ..." + उसका पेज खोलो.
 */
object OfflineExec {
    val TYPES = setOf("alarm_set", "timer_set", "camera", "info", "tap_text", "sms_send", "sms_confirm")

    suspend fun run(svc: DKAccessibilityService, a: JSONObject): String = when (a.optString("type")) {
        "alarm_set" -> alarm(svc, a)
        "timer_set" -> timer(svc, a)
        "camera" -> camera(svc, a.optBoolean("photo"), a.optBoolean("front"))
        "info" -> info(svc, a.optString("what"))
        "tap_text" -> tap(svc, a.optString("text"))
        "sms_send" -> smsAsk(svc, a.optString("name"), a.optString("text"))
        "sms_confirm" -> smsConfirm(svc, a.optBoolean("yes"))
        else -> "ERROR: unknown"
    }

    private fun granted(svc: DKAccessibilityService, p: String) = ContextCompat.checkSelfPermission(svc, p) == PackageManager.PERMISSION_GRANTED

    /** परमिशन नहीं: साफ़ बोलो कौन-सी, और DK की ऐप-जानकारी (Permissions) वाला पेज खोलो */
    private fun needPerm(svc: DKAccessibilityService, what: String): String {
        try {
            svc.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + svc.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return "ERROR_PERM: सर, $what की परमिशन नहीं है। मैंने DK का परमिशन वाला पेज खोल दिया है, वहाँ Permissions में $what चालू कर दीजिए।"
    }

    // ------------------------------------------------------------------ अलार्म / टाइमर (घड़ी ऐप)

    private fun spokenClock(h: Int, m: Int): String {
        val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$part $h12 बजे" else "$part $h12 बजकर $m मिनट"
    }

    private fun alarm(svc: DKAccessibilityService, a: JSONObject): String {
        if (a.optBoolean("ask")) return "ASK: सर, किस समय का अलार्म लगाऊँ? जैसे \"सुबह 7 बजे\"।"
        val h = a.optInt("hour", -1); val m = a.optInt("minute", 0)
        if (h !in 0..23) return "ASK: सर, समय समझ नहीं आया। किस समय का अलार्म लगाऊँ?"
        return try {
            svc.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "DK").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "SAID: हो गया सर, ${spokenClock(h, m)} का अलार्म लगा दिया।"
        } catch (e: Exception) { "STEP_FAILED: सर, अलार्म नहीं लग पाया। फ़ोन में घड़ी (Clock) ऐप नहीं मिला।" }
    }

    private fun timer(svc: DKAccessibilityService, a: JSONObject): String {
        if (a.optBoolean("ask")) return "ASK: सर, कितनी देर का टाइमर लगाऊँ?"
        val s = a.optInt("seconds", 0)
        if (s <= 0) return "ASK: सर, कितनी देर का टाइमर लगाऊँ?"
        val say = buildList {
            if (s >= 3600) add("${s / 3600} घंटे")
            if (s % 3600 >= 60) add("${s % 3600 / 60} मिनट")
            if (s % 60 > 0) add("${s % 60} सेकंड")
        }.joinToString(" ")
        return try {
            svc.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "DK").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "SAID: हो गया सर, $say का टाइमर लगा दिया।"
        } catch (e: Exception) { "STEP_FAILED: सर, टाइमर नहीं लग पाया। फ़ोन में घड़ी (Clock) ऐप नहीं मिला।" }
    }

    // ------------------------------------------------------------------ कैमरा / फ़ोटो

    private val SHUTTER = arrayOf("Shutter", "shutter", "Take photo", "Take picture", "Capture", "Take a photo", "Photo shutter",
        "शटर", "फ़ोटो लें", "फोटो लें", "फ़ोटो खींचें", "फोटो खींचें", "कैप्चर")

    private suspend fun camera(svc: DKAccessibilityService, photo: Boolean, front: Boolean): String {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (front) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        try { svc.startActivity(i) } catch (e: Exception) {
            return try { AppLauncher.open(svc, "camera"); if (photo) "SAID: सर, कैमरा खोल दिया, फ़ोटो का बटन दबा दीजिए।" else "SAID: हो गया सर।" }
            catch (_: Exception) { "STEP_FAILED: सर, फ़ोन में कैमरा ऐप नहीं खुला।" }
        }
        if (!photo) return "SAID: हो गया सर, कैमरा खोल दिया।"
        // कैमरा तैयार होने दो, फिर शटर बटन (उसके नाम/विवरण से)
        delay(1600)
        repeat(4) {
            if (svc.clickVisibleText(*SHUTTER)) return "SAID: हो गया सर, फ़ोटो खींच ली।"
            delay(600)
        }
        return "SAID: सर, कैमरा खोल दिया पर फ़ोटो वाला बटन नहीं मिला। बटन दबा दीजिए।"
    }

    // ------------------------------------------------------------------ समय / तारीख / बैटरी (फ़ोन से)

    private fun info(svc: DKAccessibilityService, what: String): String {
        val ask = when (what) { "time" -> "कितने बजे"; "day" -> "आज कौन सा दिन"; "date" -> "आज की तारीख"; else -> "बैटरी कितनी" }
        var line = LocalBrain.instant(svc, ask) ?: return "STEP_FAILED: सर, यह जानकारी फ़ोन से नहीं मिल पाई।"
        if (what == "battery") {
            val bm = svc.getSystemService(android.os.BatteryManager::class.java)
            if (bm?.isCharging == true) line = line.removeSuffix("।") + ", चार्ज हो रही है।"
        }
        return "SAID: सर, $line"
    }

    // ------------------------------------------------------------------ स्क्रीन पर लिखे पर टैप

    private fun tap(svc: DKAccessibilityService, text: String): String {
        if (text.isBlank()) return "ASK: सर, किस पर टैप करूँ?"
        if (svc.clickVisibleText(text)) return "SAID: हो गया सर।"
        // "सेटिंग्स" बोला, स्क्रीन पर "Settings" लिखा: अंग्रेज़ी रूप से भी
        val latin = NameMatch.latin(text).trim()
        if (latin.isNotBlank() && latin != text && svc.clickVisibleText(latin)) return "SAID: हो गया सर।"
        return "STEP_FAILED: सर, स्क्रीन पर \"$text\" नहीं दिखा।"
    }

    // ------------------------------------------------------------------ SMS (पहले बोलकर पूछो, फिर भेजो)

    private suspend fun smsAsk(svc: DKAccessibilityService, name: String, text: String): String {
        if (text.isBlank()) return "ASK: सर, $name को SMS में क्या लिखूँ?"
        val digits = name.filter { it.isDigit() || it == '+' }
        val (num, label) = if (digits.length >= 6) digits to digits else {
            if (!granted(svc, Manifest.permission.READ_CONTACTS)) return needPerm(svc, "कॉन्टैक्ट")
            val all = withContext(Dispatchers.IO) { try { PhoneActions.contacts(svc) } catch (_: Exception) { emptyList() } }
            val sure = NameMatch.best(name, all, { it.name }, min = 75).distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
            val near = if (sure.isEmpty()) NameMatch.best(name, all, { it.name }, min = 60).distinctBy { it.number.filter(Char::isDigit).takeLast(10) } else sure
            when {
                near.isEmpty() -> return "ERROR_NO_CONTACT: सर, \"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला।"
                sure.isEmpty() || near.size > 1 -> return "ASK: सर, \"$name\" के कई मिलते-जुलते कॉन्टैक्ट हैं: " +
                    near.take(4).joinToString(", ") { it.name } + "। किसे भेजूँ? पूरा नाम बोलिए।"
                else -> near[0].number to near[0].name
            }
        }
        OfflineCommands.pending = OfflineCommands.Pending("sms", num, label, text, System.currentTimeMillis() + OfflineCommands.CONFIRM_MS)
        return "ASK: " + OfflineCommands.askSms(label, text)
    }

    @Volatile private var lastSent = ""
    @Volatile private var lastSentAt = 0L

    private fun smsConfirm(svc: DKAccessibilityService, yes: Boolean): String {
        val p = OfflineCommands.pending ?: return "SAID: सर, भेजने के लिए कोई SMS बाकी नहीं है।"
        OfflineCommands.pending = null
        if (!yes) return "SAID: ठीक है सर, SMS नहीं भेजा।"
        val key = p.number + "|" + p.text
        if (key == lastSent && System.currentTimeMillis() - lastSentAt < 60_000) return "SAID: सर, यह SMS पहले ही भेज दिया है।"
        if (!granted(svc, Manifest.permission.SEND_SMS)) {
            // बिना परमिशन: SMS ऐप में लिखकर खोलो (user ख़ुद भेजें दबाएँ)
            return try {
                svc.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", p.text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "SAID: सर, SMS भेजने की परमिशन नहीं है, इसलिए मैसेज लिखकर खोल दिया है, भेजें दबा दीजिए। Settings में \"कॉल/कॉन्टैक्ट परमिशन दें\" से SMS की परमिशन दे दें तो आगे सीधे जाएगा।"
            } catch (_: Exception) { needPerm(svc, "SMS") }
        }
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) svc.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(p.number, null, sms.divideMessage(p.text), null, null)
            lastSent = key; lastSentAt = System.currentTimeMillis()
            "SAID: हो गया सर, ${p.name} को SMS भेज दिया।"
        } catch (e: Exception) { DKLog.d("sms: ${e.message}"); "STEP_FAILED: सर, SMS नहीं गया। सिम या नेटवर्क देख लीजिए।" }
    }
}
