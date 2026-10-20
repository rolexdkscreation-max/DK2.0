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
    @Volatile var caller: String = ""
        private set
    private var listener: PhoneStateListener? = null

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Call ki haalat pata hai? (phone permission ho tab) */
    val known get() = listener != null
    fun canAnswer() = Build.VERSION.SDK_INT >= 26 && granted(Manifest.permission.ANSWER_PHONE_CALLS)

    val ringing get() = state == TelephonyManager.CALL_STATE_RINGING || (!known && telecomRinging())

    @SuppressLint("MissingPermission")
    private fun telecomRinging(): Boolean = try {
        ctx.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (_: Exception) { false }

    // ---- ringtone dheemi (DK ko aapki baat sunai de), call khatam hote hi wapas
    private var savedRing = -1
    fun quietRinger() {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            if (savedRing < 0) savedRing = am.getStreamVolume(AudioManager.STREAM_RING)
            if (savedRing > 1) am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
        } catch (e: Exception) { DKLog.d("ringer: ${e.message}") }
    }
    fun restoreRinger() {
        if (savedRing < 0) return
        try { ctx.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_RING, savedRing, 0) } catch (_: Exception) {}
        savedRing = -1
    }
    val inCall get() = state == TelephonyManager.CALL_STATE_OFFHOOK

    @Suppress("DEPRECATION")
    fun start() {
        if (listener != null || !granted(Manifest.permission.READ_PHONE_STATE)) return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(s: Int, number: String?) {
                val prev = state
                state = s
                if (s == TelephonyManager.CALL_STATE_RINGING && prev != s) {
                    caller = nameFor(number)
                    onRinging(caller)
                }
                if (s == TelephonyManager.CALL_STATE_IDLE) { caller = ""; restoreRinger() }
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
        if (Build.VERSION.SDK_INT >= 26 && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                ctx.getSystemService(TelecomManager::class.java)?.acceptRingingCall(); return true
            } catch (e: Exception) { DKLog.d("answer: ${e.message}") }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun end(): Boolean {
        if (Build.VERSION.SDK_INT >= 28 && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                @Suppress("DEPRECATION")
                return ctx.getSystemService(TelecomManager::class.java)?.endCall() == true
            } catch (e: Exception) { DKLog.d("end: ${e.message}") }
        }
        return false
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

    companion object {
        val PERMS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS)
    }
}
