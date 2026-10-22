package com.dk.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v3.33: फ़ोन के सीधे काम बिना AI - कॉन्टैक्ट को कॉल, कॉल लिस्ट, सबसे नई फ़ोटो.
 * हर नतीजा सच्चा: "CALLING:" सिर्फ़ तब जब कॉल सच में शुरू हुई दिखे; डायलर खोलना = "DIALER:" (कॉल लगी नहीं कहते).
 * नतीजे के prefix के बाद की बात सीधे user को बोलने लायक हिंदी है (LocalRouter.userText).
 * लॉग में नंबर / पूरा कॉन्टैक्ट नहीं लिखते (निजता).
 */
object PhoneActions {
    const val CALL_PERM = Manifest.permission.CALL_PHONE
    val PHOTO_PERM: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    /** सेटिंग्स का बटन: सीधी कॉल + फ़ोटो (वैकल्पिक - न दें तो डायलर / गैलरी खुलती है) */
    val EXTRA_PERMS: Array<String> get() = arrayOf(CALL_PERM, PHOTO_PERM)

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    data class Person(val id: Long, val name: String, val number: String, val primary: Boolean, val mobile: Boolean)

    /** फ़ोन के कॉन्टैक्ट (नाम + एक नंबर) - IO thread */
    private fun contacts(ctx: Context): List<Person> {
        val out = LinkedHashMap<Long, Person>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val name = c.getString(1) ?: continue; val num = c.getString(2) ?: continue
                val p = Person(id, name, num, c.getInt(3) != 0, c.getInt(4) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                val old = out[id]
                // एक कॉन्टैक्ट के कई नंबर: पहले "मुख्य" (super primary), फिर मोबाइल, फिर पहला
                if (old == null || (p.primary && !old.primary) || (!old.primary && p.mobile && !old.mobile)) out[id] = p
            }
        }
        return out.values.toList()
    }

    /**
     * "राहुल को कॉल करो": एक ही कॉन्टैक्ट मिले तभी कॉल. कई मिलें = पूछो (कॉल नहीं). कोई नहीं = साफ़ बताओ.
     * CALL_PHONE है -> सीधी कॉल + जाँच (कॉल सच में शुरू हुई?); नहीं है -> डायलर में नंबर (user खुद हरा बटन दबाए).
     */
    suspend fun callContact(svc: DKAccessibilityService, spoken: String): String {
        val name = spoken.trim()
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3 && digits.length >= name.count { !it.isWhitespace() } - 1) return place(svc, digits, digits)
        if (!granted(svc, Manifest.permission.READ_CONTACTS))
            return "ERROR_PERM: कॉन्टैक्ट पढ़ने की परमिशन नहीं है। सेटिंग्स में \"कॉल/कॉन्टैक्ट\" परमिशन दीजिए।"
        val t0 = System.currentTimeMillis()
        val all = withContext(Dispatchers.IO) { try { contacts(svc) } catch (e: Exception) { null } }
            ?: return "ERROR_PERM: कॉन्टैक्ट नहीं पढ़ पाया।"
        // पक्का मिलान (>=75) ही कॉल; सिर्फ़ "मिलता-जुलता" (व्यंजन) हो तो पूछो - "सुमित" बोलने पर "Smita" को कॉल नहीं
        val sure = NameMatch.best(name, all, { it.name }, min = 75)
        val hits = sure.ifEmpty { NameMatch.best(name, all, { it.name }, min = 60) }
        // एक ही नंबर वाले दोहरे कॉन्टैक्ट (WhatsApp/Google दोनों में) = एक
        val uniq = hits.distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
        DKLog.d("कॉन्टैक्ट खोज: ${all.size} में से ${uniq.size} मिले (${System.currentTimeMillis() - t0} ms)")
        return when {
            uniq.isEmpty() -> "ERROR_NO_CONTACT: \"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला।"
            sure.isEmpty() -> "ASK: \"$name\" नाम का पक्का कॉन्टैक्ट नहीं मिला। मिलते-जुलते: " +
                uniq.take(5).joinToString(", ") { it.name } + "। इनमें से किसे कॉल करूँ? पूरा नाम बोलिए।"
            uniq.size > 1 -> "ASK: \"$name\" नाम के ${uniq.size} कॉन्टैक्ट हैं: " +
                uniq.take(5).joinToString(", ") { it.name } + "। किसे कॉल करूँ? पूरा नाम बोलिए।"
            else -> place(svc, uniq[0].number, uniq[0].name)
        }
    }

    private suspend fun place(svc: DKAccessibilityService, number: String, label: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(number.filter { it.isDigit() || it == '+' }))
        if (!granted(svc, CALL_PERM)) {
            return try {
                svc.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "DIALER: $label का नंबर डायलर में भर दिया है - कॉल के लिए हरा बटन दबाइए। (सीधी कॉल के लिए सेटिंग्स में \"सीधी कॉल\" परमिशन दें)"
            } catch (e: Exception) { "ERROR_LAUNCH: डायलर नहीं खुला।" }
        }
        try {
            svc.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return "ERROR_LAUNCH: कॉल शुरू नहीं हो पाई।"
        }
        // जाँच: फ़ोन सच में कॉल पर गया? (READ_PHONE_STATE हो तभी पता चलता है)
        val t0 = System.currentTimeMillis()
        if (svc.calls.known) {
            while (System.currentTimeMillis() - t0 < 4000) {
                if (svc.calls.inCall) {
                    DKLog.d("जाँच: कॉल शुरू हुई (${System.currentTimeMillis() - t0} ms)")
                    return "CALLING: $label को कॉल लग रही है।"
                }
                delay(200)
            }
            DKLog.d("जाँच: 4s में कॉल शुरू होती नहीं दिखी")
        }
        return "CALL_UNVERIFIED: $label को कॉल करने को कह दिया है, पर कॉल लगी या नहीं - पक्का पता नहीं चला।"
    }

    /** कॉल लिस्ट (recent calls) - फ़ोन का अपना डायलर खुलता है, हम कॉल-लॉग पढ़ते नहीं */
    fun callLog(ctx: Context): String = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ok call log"
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "DIALER: कॉल लिस्ट सीधे नहीं खुली, डायलर खोल दिया - वहाँ Recents देखिए।"
        } catch (_: Exception) { "ERROR_LAUNCH: कॉल लिस्ट नहीं खुली।" }
    }

    fun openDialer(ctx: Context): String = try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok dialer"
    } catch (e: Exception) { "ERROR_LAUNCH: डायलर नहीं खुला।" }

    /**
     * सबसे नई फ़ोटो: फ़ोटो परमिशन हो तो MediaStore से सबसे नई तस्वीर खोलो; न हो तो गैलरी खोलकर सच बताओ.
     * (कभी कुछ मिटाते नहीं - मिटाना Android की अपनी पुष्टि वाली स्क्रीन से ही होता है)
     */
    suspend fun latestPhoto(svc: DKAccessibilityService): String {
        val perm = granted(svc, PHOTO_PERM) ||
            (Build.VERSION.SDK_INT >= 34 && granted(svc, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        if (!perm) {
            val r = AppLauncher.open(svc, "gallery")
            return if (r.startsWith("ERROR")) r
            else "GALLERY_OPENED: गैलरी खोल दी (सबसे नई फ़ोटो आम तौर पर सबसे ऊपर होती है)। सीधे सबसे नई फ़ोटो खोलने के लिए सेटिंग्स में फ़ोटो परमिशन दीजिए।"
        }
        val uri = withContext(Dispatchers.IO) {
            try {
                svc.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                    null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
                }
            } catch (e: Exception) { null }
        } ?: return "ERROR_NO_PHOTO: फ़ोन में कोई फ़ोटो नहीं मिली।"
        val before = svc.currentPackage()
        try {
            svc.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            return "ERROR_LAUNCH: फ़ोटो खोलने वाला ऐप नहीं मिला।"
        }
        val opened = svc.waitForeground(3000) { it.isNotBlank() && it != before && it != svc.packageName }
        return if (opened) "PHOTO_OPENED: सबसे नई फ़ोटो खोल दी।"
        else "PHOTO_OPENED: सबसे नई फ़ोटो खोलने को कह दिया (स्क्रीन पर खुली या नहीं, पक्का नहीं दिखा)।"
    }
}
