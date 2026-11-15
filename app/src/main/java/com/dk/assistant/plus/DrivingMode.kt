package com.dk.assistant.plus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import com.dk.assistant.DKLog

/**
 * 15 नवंबर 2026 (मालिक, Call & Driving, default बंद): "DK, turn on driving mode" -> आने वाली कॉल अपने-आप कटे और
 * उसी नंबर पर SMS जवाब जाए (मालिक का अपना संदेश, `{user_name}` = प्रोफ़ाइल का नाम). "ड्राइविंग मोड बंद" = पहले जैसा.
 * एक ही नंबर को 10 मिनट में दोबारा SMS नहीं (बार-बार कॉल करे तो SMS की बाढ़ नहीं).
 */
object DrivingMode {
    const val MSG = "plus_driving_msg"
    const val DEFAULT_MSG = "नमस्ते, {user_name} अभी गाड़ी चला रहे हैं। थोड़ी देर बाद खुद कॉल करेंगे। - DK AI"
    private const val REPEAT_MS = 10 * 60_000L
    private val lastSent = HashMap<String, Long>()

    fun on(c: Context) = FeatureFlags.on(c, FeatureFlags.DRIVING_MODE)

    fun set(c: Context, v: Boolean): String {
        FeatureFlags.set(c, FeatureFlags.DRIVING_MODE, v)
        return if (v) "ड्राइविंग मोड चालू। कॉल अपने-आप कटेंगी और SMS से जवाब जाएगा।" else "ड्राइविंग मोड बंद।"
    }

    /** Pure: संदेश में नाम भरना */
    fun fill(template: String, userName: String): String =
        template.ifBlank { DEFAULT_MSG }.replace("{user_name}", userName.ifBlank { "मालिक" })

    /** Pure: इस नंबर को अभी SMS भेजें? */
    fun shouldSms(number: String, now: Long, last: Map<String, Long>): Boolean {
        if (number.filter { it.isDigit() }.length < 6) return false
        val t = last[number] ?: return true
        return now - t >= REPEAT_MS
    }

    /** कॉल बज रही है - ड्राइविंग मोड हो तो काटो + SMS. true = संभाल लिया (आगे घोषणा वगैरह नहीं) */
    @SuppressLint("MissingPermission")
    fun onRinging(c: Context, number: String?): Boolean {
        if (!on(c)) return false
        var cut = false
        if (Build.VERSION.SDK_INT >= 28 && granted(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
            cut = try { @Suppress("DEPRECATION") (c.getSystemService(TelecomManager::class.java)?.endCall() == true) } catch (e: Exception) { DKLog.d("Driving endCall: ${e.message}"); false }
        }
        val n = number.orEmpty()
        val now = System.currentTimeMillis()
        if (granted(c, Manifest.permission.SEND_SMS) && synchronized(lastSent) { shouldSms(n, now, lastSent) }) {
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                val text = fill(FeatureFlags.str(c, MSG, DEFAULT_MSG), com.dk.assistant.Prefs.userName(c))
                sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null)
                synchronized(lastSent) { lastSent[n] = now }
            } catch (e: Exception) { DKLog.d("Driving SMS: ${e.message}") }
        }
        return cut
    }

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
