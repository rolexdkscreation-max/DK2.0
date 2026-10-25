package com.dk.assistant

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v3.67: "आखिरी फोटो / स्क्रीनशॉट डिलीट करो" - सच में, बिना AI एजेंट के.
 *
 * Android 11+ का MediaStore.createTrashRequest: Android खुद "Allow / Deny" पूछता है (यही पुष्टि है),
 * फ़ाइल ट्रैश में जाती है (30 दिन तक गैलरी से वापस). फिर दोबारा पढ़कर जाँचते हैं कि सच में ट्रैश में गई -
 * तभी "DELETED:", वरना सच ("ERROR_DENIED:" / "DELETE_UNVERIFIED:"). Android 10 या पुराना = साफ़ मना.
 * फ़ाइल के नाम/पते log नहीं होते.
 */
object MediaDelete {
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    @Volatile var sender: android.content.IntentSender? = null
        private set

    private const val ASK_MS = 60_000L

    suspend fun latest(svc: DKAccessibilityService, kind: String, count: Int): String {
        if (Build.VERSION.SDK_INT < 30)
            return "ERROR_OLD_ANDROID: इस फ़ोन का Android (10 या पुराना) DK को फ़ोटो मिटाने की इजाज़त-वाली खिड़की नहीं देता - गैलरी से खुद मिटाइए।"
        val perm = PhoneActions.granted(svc, PhoneActions.PHOTO_PERM)
        if (!perm) return "ERROR_PERM: फ़ोटो मिटाने के लिए फ़ोटो की परमिशन चाहिए - सेटिंग्स → परमिशन → फ़ोटो दीजिए।"
        val n = count.coerceIn(1, 20)
        val shot = kind == "screenshot"
        val uris = withContext(Dispatchers.IO) { newest(svc, shot, n) }
        if (uris.isEmpty()) return "ERROR_NO_PHOTO: फ़ोन में कोई ${if (shot) "स्क्रीनशॉट" else "फ़ोटो"} नहीं मिला।"
        val what = (if (uris.size == 1) "" else "${uris.size} ") + (if (shot) "स्क्रीनशॉट" else "फ़ोटो")

        val pi = try { MediaStore.createTrashRequest(svc.contentResolver, uris, true) }
            catch (e: Exception) { DKLog.d("मिटाना: Android ने मना किया ${e.javaClass.simpleName}"); return "ERROR_LAUNCH: Android ने मिटाने की खिड़की नहीं खोली।" }
        val wait = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = wait; sender = pi.intentSender
        try {
            svc.startActivity(Intent(svc, DeleteMediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            pending = null; sender = null
            return "ERROR_LAUNCH: मिटाने की खिड़की नहीं खुली।"
        }
        val yes = withTimeoutOrNull(ASK_MS) { wait.await() } ?: false
        pending = null; sender = null
        if (!yes) return "ERROR_DENIED: आपने Allow नहीं दबाया - कुछ नहीं मिटाया।"
        // सच में ट्रैश में गई? (Android कभी-कभी OK देकर भी कुछ नहीं करता)
        val left = withContext(Dispatchers.IO) { uris.count { stillThere(svc, it) } }
        DKLog.d("मिटाना: ${uris.size} में से ${uris.size - left} ट्रैश में")
        return when {
            left == 0 -> "DELETED: आखिरी $what मिटा दी (ट्रैश में - 30 दिन तक गैलरी से वापस ला सकते हैं)।"
            left < uris.size -> "DELETE_UNVERIFIED: ${uris.size} में से ${uris.size - left} ही मिटीं, $left वैसी ही हैं।"
            else -> "DELETE_UNVERIFIED: Allow के बाद भी फ़ोटो वहीं है - मिटना पक्का नहीं हुआ।"
        }
    }

    internal fun done(ok: Boolean) { pending?.complete(ok) }

    private fun newest(svc: DKAccessibilityService, shot: Boolean, n: Int): List<Uri> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Uri>()
        val sel = if (shot) "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)" else null
        val args = if (shot) arrayOf("%Screenshot%", "Screenshot%") else null
        try {
            svc.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (out.size < n && c.moveToNext()) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
            }
        } catch (e: Exception) { DKLog.d("मिटाना: फ़ोटो सूची नहीं पढ़ी ${e.javaClass.simpleName}") }
        return out
    }

    /** ट्रैश में नहीं गई / अब भी आम फ़ोटो है? (ट्रैश वाली आम query में नहीं दिखती) */
    private fun stillThere(svc: DKAccessibilityService, u: Uri): Boolean = try {
        svc.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: true
    } catch (_: Exception) { true }                 // पढ़ नहीं पाए = पक्का नहीं, "मिटा दी" नहीं कहेंगे
}

/** पारदर्शी, बिना UI: Android की "Allow / Deny" खिड़की खोलो, जवाब MediaDelete को दो */
class DeleteMediaActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = MediaDelete.sender
        if (s == null) { finish(); return }
        try {
            @Suppress("DEPRECATION") startIntentSenderForResult(s, REQ, null, 0, 0, 0)
        } catch (e: Exception) {
            answered = true; MediaDelete.done(false); finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        answered = true
        MediaDelete.done(resultCode == RESULT_OK)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!answered && isFinishing) MediaDelete.done(false)
        super.onDestroy()
    }

    companion object { private const val REQ = 4311 }
}
