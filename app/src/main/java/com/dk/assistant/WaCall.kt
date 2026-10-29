package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v4: WhatsApp पर आवाज़ / वीडियो कॉल - "व्हाट्सएप पर राहुल को कॉल करो", "राहुल को व्हाट्सएप वीडियो कॉल लगाओ".
 * पहले: "व्हाट्सएप पर राहुल" को ही नाम समझकर SIM कॉल या "कॉन्टैक्ट नहीं मिला".
 * अब: फ़ोन के कॉन्टैक्ट में WhatsApp जो "कॉल" वाली लाइन रखता है, उसी से सीधे WhatsApp कॉल (स्क्रीन पर बटन ढूँढे बिना).
 */
object WaCall {
    const val VOICE_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    const val VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    const val W4B_VOICE_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call"
    const val W4B_VIDEO_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call"

    data class Cmd(val name: String, val video: Boolean)

    private val WA = Regex("(?<![\\p{L}\\p{M}])(?:व्हाट्सएप|व्हाट्सऐप|वॉट्सऐप|वॉट्सएप|वाट्सएप|वाट्सऐप|व्हाट्सअप|वाट्सअप|व्हाट्सप|वाट्सप|whatsapp|whats app|watsapp|whatsap|wa)(?![\\p{L}\\p{M}])")
    private val CALL = Regex("(?<![\\p{L}\\p{M}])(?:कॉल|काल|call|फोन|फ़ोन)(?![\\p{L}\\p{M}])")
    private val VIDEO = Regex("(?<![\\p{L}\\p{M}])(?:वीडियो|विडियो|विडिओ|वीडियोकॉल|video|videocall)(?![\\p{L}\\p{M}])")
    /** आने वाली कॉल / मैसेज / कॉल का इतिहास = यह काम नहीं */
    private val NOT_THIS = Regex("(?:उठा|काट|कट |रिसीव|receive|answer|reject|decline|मैसेज|मेसेज|message|msg|लिख|भेज|send|किसका|कौन|who|आया|आई|आ रहा|आ रही|आने|मिस्ड|missed|इतिहास|history|लॉग|log|मत )")
    private val STOP = setOf(
        "पर", "में", "से", "पे", "par", "pe", "on", "in", "via", "through", "using", "से", "द्वारा",
        "को", "ko", "to", "का", "की", "के", "ka", "ki", "ke",
        "करो", "कर", "दो", "दें", "दीजिए", "कीजिए", "करिए", "करना", "करें", "लगाओ", "लगा", "लगाना", "लगाइए", "मिलाओ", "मिला", "मिलाना",
        "karo", "kar", "do", "lagao", "laga", "milao", "please", "प्लीज़", "प्लीज", "ज़रा", "जरा", "अभी", "abhi", "एक", "ek",
        "make", "a", "an", "the", "place", "start", "give", "ring",
        "वॉइस", "वॉयस", "voice", "ऑडियो", "audio", "नॉर्मल", "normal", "आवाज़", "आवाज",
    )

    /** बोले वाक्य से (नाम, वीडियो?) - WhatsApp + कॉल दोनों हों तभी. QuickCommands जैसा साफ़ (छोटे अक्षर, wake word हटे) text */
    fun parse(raw: String): Cmd? {
        var t = " " + raw.lowercase().replace(Regex("[.,!?।]"), " ") + " "
        if (!WA.containsMatchIn(t) || !CALL.containsMatchIn(t) || NOT_THIS.containsMatchIn(t)) return null
        val video = VIDEO.containsMatchIn(t)
        t = WA.replace(t, " "); t = CALL.replace(t, " "); t = VIDEO.replace(t, " ")
        t = t.replace(Regex("(?<![\\p{L}\\p{M}])(?:वीडियो|video)\\s*(?:कॉल|call)(?![\\p{L}\\p{M}])"), " ")
        val name = t.split(Regex("\\s+")).filter { it.isNotBlank() && it !in STOP }.joinToString(" ").trim()
        if (name.isBlank() || name.length > 40) return null
        return Cmd(name, video)
    }

    fun toJson(c: Cmd) = JSONObject().put("type", "wa_call").put("name", c.name).put("video", c.video)
    fun fromJson(o: JSONObject) = Cmd(o.optString("name").trim(), o.optBoolean("video", false))

    internal fun digits(s: String) = s.filter { it.isDigit() }

    data class Row(val dataId: Long, val name: String, val number: String, val mime: String)

    /** WhatsApp पर कौन से कॉन्टैक्ट हैं (WhatsApp खुद कॉन्टैक्ट में "Voice call / Video call" लाइन जोड़ता है) */
    internal fun rows(ctx: Context, video: Boolean): List<Row> {
        val mimes = if (video) listOf(VIDEO_MIME, W4B_VIDEO_MIME) else listOf(VOICE_MIME, W4B_VOICE_MIME)
        val out = ArrayList<Row>()
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1, ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString { "?" }})", mimes.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) out += Row(c.getLong(0), c.getString(1) ?: "", (c.getString(2) ?: "").substringBefore('@'), c.getString(3) ?: "")
        }
        // WhatsApp पहले, Business बाद में; एक ही नंबर दो बार नहीं
        return out.sortedBy { if (it.mime.contains("w4b")) 1 else 0 }.distinctBy { it.name.lowercase() + "|" + digits(it.number).takeLast(10) }
    }

    /** नाम (या बोला नंबर) से WhatsApp कॉल. नतीजा: CALLING: / ASK: / ERROR_...: (LocalRouter.userText यही बोलता है) */
    suspend fun call(svc: DKAccessibilityService, spoken: String, video: Boolean): String {
        val kind = if (video) "वीडियो कॉल" else "कॉल"
        if (spoken.isBlank()) return "ASK: WhatsApp पर किसे $kind करूँ?"
        if (androidx.core.content.ContextCompat.checkSelfPermission(svc, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "ERROR_PERM: WhatsApp कॉल के लिए DK को कॉन्टैक्ट पढ़ने की इजाज़त चाहिए - Settings → Apps → DK → Permissions → Contacts।"
        val all = withContext(Dispatchers.IO) { try { rows(svc, video) } catch (e: Exception) { DKLog.d("WaCall rows: ${e.message}"); emptyList() } }
        if (all.isEmpty()) return "ERROR_NO_CONTACT: फ़ोन के कॉन्टैक्ट में WhatsApp वाला कोई नहीं मिला (WhatsApp → Settings → Contacts sync चालू करें)।"
        val sd = digits(spoken)
        val hits = if (sd.length >= 6) all.filter { digits(it.number).endsWith(sd.takeLast(10)) }
            else NameMatch.best(spoken, all, { it.name }, min = 75)
        val people = hits.distinctBy { it.name.lowercase() }
        return when {
            people.isEmpty() -> "ERROR_NO_CONTACT: \"$spoken\" नाम का कोई WhatsApp पर नहीं मिला।"
            people.size > 1 -> "ASK: WhatsApp पर किसे $kind करूँ - ${people.take(4).joinToString(" या ") { it.name }}?"
            else -> {
                val r = hits.first { it.name.equals(people[0].name, true) }
                val pkg = if (r.mime.contains("w4b")) "com.whatsapp.w4b" else "com.whatsapp"
                try {
                    svc.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, r.dataId.toString()), r.mime)
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: android.content.ActivityNotFoundException) {
                    return "ERROR_NOT_INSTALLED: फ़ोन में WhatsApp नहीं मिला।"
                } catch (e: Exception) {
                    DKLog.d("WaCall start: ${e.message}")
                    return "ERROR_LAUNCH: WhatsApp $kind नहीं खुल पाई।"
                }
                delay(2500)
                val fg = try { svc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                if (fg == null || fg == pkg) "CALLING: ${r.name} को WhatsApp $kind लगा रहा हूँ।"
                else "CALL_UNVERIFIED: ${r.name} को WhatsApp $kind लगाने की कोशिश की, पर WhatsApp सामने नहीं दिखा।"
            }
        }
    }
}
