package com.dk.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Phone call: kiska call aa raha hai batana, uthana, speaker, kaatna. */
class CallManager(private val ctx: Context, private val onRinging: (String) -> Unit) {

    @Volatile var state = TelephonyManager.CALL_STATE_IDLE
        private set
    @Volatile private var simCaller: String = ""
    /** v4.1: SIM कॉल का असली नंबर (काटकर SMS भेजने के लिए) - कॉल ख़त्म होने के कुछ सेकंड बाद तक रहता है */
    @Volatile var simNumber: String = ""
        private set
    /** v4: SIM कॉल का नाम, नहीं तो WhatsApp/Instagram… वाली कॉल का */
    val caller: String get() = simCaller.ifBlank { if (AppCall.active()) AppCall.who() else "" }
    private var listener: PhoneStateListener? = null

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Call ki haalat pata hai? (phone permission ho tab) */
    val known get() = listener != null
    fun canAnswer() = Build.VERSION.SDK_INT >= 26 && granted(Manifest.permission.ANSWER_PHONE_CALLS)

    /** SIM वाली कॉल बज रही है */
    val simRinging get() = state == TelephonyManager.CALL_STATE_RINGING || (!known && telecomRinging())
    /** v4: SIM या किसी ऐप (WhatsApp/Instagram/Facebook/Messenger…) की कॉल बज रही है */
    val ringing get() = simRinging || AppCall.ringing()

    @SuppressLint("MissingPermission")
    private fun telecomRinging(): Boolean = try {
        ctx.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (_: Exception) { false }

    // ---- ringtone dheemi (DK ko aapki baat sunai de), call khatam hote hi wapas
    // v3.6: पहले का ringtone volume फ़ाइल में भी (DK बीच में बंद/crash = घंटी हमेशा 1 पर रह जाती थी - owner की शिकायत)
    private val ringPrefs get() = ctx.getSharedPreferences("dk_ring", Context.MODE_PRIVATE)
    private var savedRing: Int
        get() = ringPrefs.getInt("saved", -1)
        set(v) { ringPrefs.edit().putInt("saved", v).putLong("at", System.currentTimeMillis()).apply() }
    private val ringH = android.os.Handler(android.os.Looper.getMainLooper())
    private val ringSafety = object : Runnable {
        override fun run() {
            if (savedRing < 0) return
            if (RingGuard.shouldRestore(ringing || inCall)) { DKLog.d("घंटी: कॉल ख़त्म - आवाज़ वापस"); restoreRinger() }
            else ringH.postDelayed(this, RingGuard.CHECK_MS)
        }
    }
    fun quietRinger() {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        ringH.removeCallbacks(ringSafety); ringH.postDelayed(ringSafety, RingGuard.CHECK_MS)
        try {
            if (savedRing < 0) savedRing = am.getStreamVolume(AudioManager.STREAM_RING)
            if (am.getStreamVolume(AudioManager.STREAM_RING) > 1) am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
        } catch (e: Exception) {
            // kuch phone seedha set nahi karne dete -> ek-ek step kam karo (0 tak nahi = DND ki zarurat nahi)
            DKLog.d("ringer: ${e.message}")
            try { repeat(10) { if (am.getStreamVolume(AudioManager.STREAM_RING) > 1) am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, 0) } }
            catch (_: Exception) {}
        }
    }
    fun restoreRinger() {
        val v = savedRing
        if (v < 0) return
        ringH.removeCallbacks(ringSafety)
        try { ctx.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_RING, v, 0) } catch (_: Exception) {}
        ringPrefs.edit().remove("saved").remove("at").apply()
    }
    /** SIM कॉल चल रही है, या v4: ऐप वाली कॉल उठ चुकी है */
    val inCall get() = state == TelephonyManager.CALL_STATE_OFFHOOK || (state == TelephonyManager.CALL_STATE_IDLE && AppCall.ongoing())

    @Suppress("DEPRECATION")
    fun start() {
        // v3.6: पिछली बार DK बीच में बंद हुआ और घंटी धीमी रह गई = अभी कोई कॉल नहीं तो वापस
        if (savedRing >= 0 && RingGuard.shouldRestore(ringing || inCall)) { DKLog.d("घंटी: पिछली बार धीमी रह गई थी - वापस"); restoreRinger() }
        if (listener != null || !granted(Manifest.permission.READ_PHONE_STATE)) return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(s: Int, number: String?) {
                val prev = state
                state = s
                if (s == TelephonyManager.CALL_STATE_RINGING && prev != s) {
                    simNumber = number.orEmpty()
                    simCaller = nameFor(number)
                    onRinging(simCaller)
                }
                if (s == TelephonyManager.CALL_STATE_IDLE) { simCaller = ""; restoreRinger(); if (prev != s) CallCmd.markEnded(System.currentTimeMillis()) }
                if (s == TelephonyManager.CALL_STATE_OFFHOOK) restoreRinger()
            }
        }
        try {
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            listener = l
        } catch (e: Exception) { DKLog.d("Call listener: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        listener?.let { ctx.getSystemService(TelephonyManager::class.java)?.listen(it, PhoneStateListener.LISTEN_NONE) }
        listener = null
    }

    /** Number -> contact ka naam (permission ho to). Khali = pata nahi. */
    private fun nameFor(number: String?): String {
        if (number.isNullOrBlank()) return ""
        if (granted(Manifest.permission.READ_CONTACTS)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {}
        }
        return number.chunked(5).joinToString(" ")   // number ko tukdon me bolo
    }

    @SuppressLint("MissingPermission")
    fun answer(): Boolean {
        // v4: ऐप (WhatsApp…) की कॉल - उसी notification का Answer बटन (SIM कॉल न बज रही हो तो)
        if (!simRinging && AppCall.ringing()) return AppCall.answer(ctx)
        if (Build.VERSION.SDK_INT >= 26 && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                ctx.getSystemService(TelecomManager::class.java)?.acceptRingingCall(); return true
            } catch (e: Exception) { DKLog.d("answer: ${e.message}") }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun end(): Boolean {
        // v4: ऐप की कॉल - बजती = Decline, चल रही = Hang up (SIM कॉल न हो तो)
        if (!simRinging && state == TelephonyManager.CALL_STATE_IDLE && AppCall.active()) return AppCall.end(ctx)
        if (Build.VERSION.SDK_INT >= 28 && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                @Suppress("DEPRECATION")
                return ctx.getSystemService(TelecomManager::class.java)?.endCall() == true
            } catch (e: Exception) { DKLog.d("end: ${e.message}") }
        }
        return false
    }

    /** v4.1: कॉल में अपना माइक बंद/चालू (SIM कॉल). false = फ़ोन ने नहीं माना */
    fun muteApi(on: Boolean): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        return try { am.isMicrophoneMute = on; am.isMicrophoneMute == on } catch (e: Exception) { DKLog.d("mute: ${e.message}"); false }
    }

    @Suppress("DEPRECATION")
    fun speakerApi(on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            }
            am.isSpeakerphoneOn = on
        } catch (e: Exception) { DKLog.d("speaker: ${e.message}") }
    }

    /** Speaker abhi chalu hai? (pata na chale to null) */
    @Suppress("DEPRECATION")
    fun speakerIsOn(): Boolean? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: am.isSpeakerphoneOn
            else am.isSpeakerphoneOn
        } catch (e: Exception) { null }
    }

    companion object {
        val PERMS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS)   // v4.1: कॉल काटकर SMS
    }
}
