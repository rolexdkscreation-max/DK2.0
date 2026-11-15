package com.dk.assistant.plus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telephony.TelephonyManager
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 15 नवंबर 2026 (मालिक, Advanced → Emergency SOS, default बंद):
 * "DK, activate SOS" -> ऊपर के 5 इमरजेंसी नंबरों को तुरंत (1) SMS (2) WhatsApp पर Google Maps लोकेशन लिंक,
 * फिर (3) क्रम से कॉल - हर नंबर पर 5 बार तक, जो उठा ले वहीं रुकना (कॉल-लॉग की duration > 0 = उठाया).
 * शुरू होने से पहले [Rules.COUNTDOWN_SEC] सेकंड की उल्टी गिनती (गलती से चालू हुआ हो तो रद्द करने का समय).
 * "SOS बंद" / "SOS रोको" = तुरंत सब रुकता है. नियम (pure) [Rules] में - JVM test ([SosTest]).
 */
object Sos {
    const val CONTACTS = "plus_sos_contacts"
    const val MAX_CONTACTS = 5
    const val TRIES_PER_CONTACT = 5

    object Rules {
        /** "+91 98765-43210, 98765 43211" -> साफ़ नंबर (ज़्यादा से ज़्यादा 5, दोहराव नहीं) */
        fun parse(raw: String): List<String> = raw.split(',', '\n', ';')
            .map { norm(it) }.filter { it.length >= 10 }.distinct().take(MAX_CONTACTS)

        fun norm(n: String): String {
            val plus = n.trim().startsWith("+")
            val d = n.filter { it.isDigit() }
            return if (plus) "+$d" else d
        }

        /** WhatsApp के लिए देश-कोड वाला नंबर (10 अंक = भारत +91) */
        fun waNumber(n: String): String {
            val d = n.filter { it.isDigit() }
            return when {
                d.length == 10 -> "91$d"
                d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
                else -> d
            }
        }

        fun mapsLink(lat: Double, lon: Double): String =
            "https://maps.google.com/?q=" + String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)

        fun message(userName: String, lat: Double?, lon: Double?): String = buildString {
            val who = userName.ifBlank { "DK AI के मालिक" }
            append("SOS! $who को अभी मदद चाहिए। कृपया तुरंत संपर्क करें।")
            if (lat != null && lon != null) append(" लाइव लोकेशन: ").append(mapsLink(lat, lon))
            else append(" (लोकेशन नहीं मिल पाई)")
            append(" - DK AI")
        }

        /** 15 नवंबर 2026 (ऑडिट): SMS/कॉल से पहले इतने सेकंड - गलती से चालू हुआ हो तो "SOS रोको" बोलकर रद्द */
        const val COUNTDOWN_SEC = 10

        fun countdownSpeech(sec: Int = COUNTDOWN_SEC): String =
            "$sec सेकंड में इमरजेंसी SOS भेजूँगी। गलती से हुआ हो तो बोलें: SOS रोको।"

        /** कॉल का क्रम: (नंबर, कोशिश) - पहले नंबर की 5 कोशिश, फिर दूसरे की ... */
        fun callPlan(contacts: List<String>, tries: Int = TRIES_PER_CONTACT): List<Pair<String, Int>> =
            contacts.flatMap { n -> (1..tries).map { n to it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    val running get() = job?.isActive == true

    fun contacts(c: Context): List<String> = Rules.parse(FeatureFlags.str(c, CONTACTS))

    /** null = शुरू हुआ (बोलने लायक संदेश वापस), वरना वजह */
    @Synchronized fun start(c: Context): String {
        val app = c.applicationContext
        if (!FeatureFlags.on(app, FeatureFlags.SOS_ON)) return "इमरजेंसी SOS बंद है। सेटिंग्स → टच मोड → इमरजेंसी SOS में चालू करें।"
        val list = contacts(app)
        if (list.isEmpty()) return "SOS के लिए कोई नंबर नहीं डाला है। सेटिंग्स में 5 इमरजेंसी नंबर डालें।"
        if (running) return "SOS पहले से चल रहा है।"
        // उल्टी गिनती की घोषणा फ़ोन की अपनी आवाज़ से - Live/चैट चाहे जो भी चल रहा हो, मालिक को सुनाई दे
        PlusVoice.say(app, Rules.countdownSpeech())
        job = scope.launch {
            // पहले उल्टी गिनती: इस बीच "SOS रोको" = stop() = job रद्द, कोई SMS/कॉल नहीं जाता
            delay(Rules.COUNTDOWN_SEC * 1000L)
            if (!currentCoroutineContext().isActive) return@launch
            PlusVoice.say(app, "SOS भेज रही हूँ।")
            runSos(app, list)
        }
        return Rules.countdownSpeech() + " फिर ${list.size} लोगों को लोकेशन भेजकर कॉल करूँगी।"
    }

    @Synchronized fun stop(): String { val was = running; job?.cancel(); job = null; return if (was) "SOS रोक दिया।" else "SOS चल नहीं रहा था।" }

    private suspend fun runSos(c: Context, list: List<String>) {
        val loc = location(c)
        val msg = Rules.message(com.dk.assistant.Prefs.userName(c), loc?.latitude, loc?.longitude)
        // 1) SMS - सबको
        if (granted(c, Manifest.permission.SEND_SMS)) list.forEach { n ->
            try {
                val sms = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                sms.sendMultipartTextMessage(n, null, sms.divideMessage(msg), null, null)
            } catch (e: Exception) { DKLog.d("SOS SMS $n: ${e.message}") }
        }
        // 2) WhatsApp - Accessibility चालू हो तो "भेजें" भी अपने-आप (retry-loop से पक्का)
        val svc = DKAccessibilityService.instance
        if (svc != null) list.forEach { n ->
            if (!currentCoroutineContext().isActive) return
            try {
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${Rules.waNumber(n)}&text=" + Uri.encode(msg)))
                        .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                delay(3500)
                val r = A11yRetry.click(svc, "Send", viewId = "com.whatsapp:id/send")
                DKLog.d("SOS WhatsApp $n: ${r.report()}")
            } catch (e: Exception) { DKLog.d("SOS WhatsApp $n: ${e.message}") }
        }
        PlusVoice.say(c, "SOS संदेश भेज दिए। अब कॉल कर रही हूँ।")
        // 3) कॉल - क्रम से, जो उठा ले वहीं बस
        if (!granted(c, Manifest.permission.CALL_PHONE)) return
        for ((n, attempt) in Rules.callPlan(list)) {
            if (!currentCoroutineContext().isActive) return
            DKLog.d("SOS कॉल: $n (कोशिश $attempt)")
            val started = System.currentTimeMillis()
            try {
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) { DKLog.d("SOS call: ${e.message}"); continue }
            waitCallEnd(c)
            delay(2000)
            if (answered(c, n, started)) { PlusVoice.say(c, "SOS कॉल उठा ली गई।"); return }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun waitCallEnd(c: Context) {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: run { delay(45_000); return }
        val t0 = System.currentTimeMillis()
        var sawOffhook = false
        while (System.currentTimeMillis() - t0 < 120_000) {
            val st = try { tm.callState } catch (_: Exception) { TelephonyManager.CALL_STATE_IDLE }
            if (st == TelephonyManager.CALL_STATE_OFFHOOK) sawOffhook = true
            if (sawOffhook && st == TelephonyManager.CALL_STATE_IDLE) return
            if (!sawOffhook && System.currentTimeMillis() - t0 > 15_000) return
            delay(1000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun answered(c: Context, number: String, since: Long): Boolean {
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return false
        return try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?", arrayOf((since - 5000).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val num = cur.getString(0).orEmpty().filter { it.isDigit() }.takeLast(10)
                    if (num == number.filter { it.isDigit() }.takeLast(10) && cur.getLong(1) > 0) return true
                }
                false
            } ?: false
        } catch (_: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun location(c: Context): Location? {
        if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(c, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
