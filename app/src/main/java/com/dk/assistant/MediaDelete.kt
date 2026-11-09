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

    /** पुराना रास्ता ("आखिरी 1-20") - अब [run] से */
    suspend fun latest(svc: DKAccessibilityService, kind: String, count: Int): String =
        run(svc, MediaPick.Spec(if (kind == "screenshot") "screenshot" else "photo", count.coerceIn(1, 20)))

    /**
     * नवंबर 2026: गिनती 500 तक, दिन (आज / कल / परसों), ऊपर से (नई) / नीचे से (पुरानी), उस दिन की सारी.
     * "मीडिया मैनेजमेंट" (MANAGE_MEDIA, Android 12+) की इजाज़त हो तो Android Allow नहीं पूछता = फटाक से.
     */
    suspend fun run(svc: DKAccessibilityService, spec: MediaPick.Spec): String {
        if (Build.VERSION.SDK_INT < 30)
            return "ERROR_OLD_ANDROID: इस फ़ोन का Android (10 या पुराना) DK को फ़ोटो मिटाने की इजाज़त-वाली खिड़की नहीं देता - गैलरी से खुद मिटाइए।"
        // नवंबर 2026 (मालिक: "फ़ोटो डिलीट नहीं हो रही"): "सारी फ़ाइलें" (MANAGE_EXTERNAL_STORAGE) वाली इजाज़त भी चलती है;
        // Android 14 पर सिर्फ़ "चुनी हुई फ़ोटो" की इजाज़त = DK को बाकी फ़ोटो दिखती ही नहीं -> गलत फ़ोटो न मिटे, सच बताओ
        val full = PhoneActions.granted(svc, PhoneActions.PHOTO_PERM) || allFiles()
        if (!full) {
            return if (Build.VERSION.SDK_INT >= 34 && PhoneActions.granted(svc, PARTIAL))
                "ERROR_PERM: DK को सिर्फ़ आपकी चुनी हुई कुछ फ़ोटो दिखती हैं - इसलिए सही फ़ोटो नहीं मिटा सकता। फ़ोन की सेटिंग्स → ऐप्स → DK → परमिशन → फ़ोटो और वीडियो → \"हमेशा सारी फ़ोटो की इजाज़त\" (सिर्फ़ चुनी हुई नहीं, सारी फ़ोटो) चुनिए, फिर दोबारा बोलिए।"
            else "ERROR_PERM: फ़ोटो मिटाने के लिए फ़ोटो की परमिशन चाहिए - फ़ोन की सेटिंग्स → ऐप्स → DK → परमिशन → फ़ोटो और वीडियो → \"सारी फ़ोटो की इजाज़त\" दीजिए।"
        }
        val n = spec.count.coerceIn(1, MediaPick.MAX)
        val range = if (spec.day >= 0) MediaPick.dayRange(spec.day, System.currentTimeMillis()) else null
        val uris = withContext(Dispatchers.IO) { pick(svc, spec.shot, n, range, spec.from == MediaPick.From.OLD) }
        if (uris.isEmpty()) {
            val d = when (spec.day) { 0 -> "आज का "; 1 -> "कल का "; 2 -> "परसों का "; else -> "" }
            return "ERROR_NO_PHOTO: फ़ोन में ${d}कोई ${if (spec.shot) "स्क्रीनशॉट" else "फ़ोटो"} नहीं मिला।"
        }
        val what = spec.hi(uris.size)
        val silent = canManage(svc)

        val pi = try { MediaStore.createTrashRequest(svc.contentResolver, uris, true) }
            catch (e: Exception) { DKLog.d("मिटाना: Android ने मना किया ${e.javaClass.simpleName}"); return "ERROR_LAUNCH: Android ने मिटाने की खिड़की नहीं खोली।" }
        val wait = CompletableDeferred<Boolean>()
        pending?.complete(false)
        pending = wait; sender = pi.intentSender; opened = false
        try {
            svc.startActivity(Intent(svc, DeleteMediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            DKLog.d("मिटाना: खिड़की सीधे नहीं खुली ${e.javaClass.simpleName} - सूचना से खोलेंगे")
        }
        // नवंबर 2026 (मालिक: "फ़ोटो डिलीट नहीं हो रही"): MIUI / Vivo / Oppo वगैरह \"पीछे से पॉप-अप\" रोक देते हैं -
        // Allow वाली खिड़की खुलती ही नहीं थी और 60 सेकंड बाद \"आपने Allow नहीं दबाया\" कहते थे. अब खिड़की खुली या नहीं देखते;
        // न खुले तो सूचना (टैप = वही Allow खिड़की) और इंतज़ार जारी.
        var notified = false
        if (withTimeoutOrNull(OPEN_MS) { while (!opened && !wait.isCompleted) kotlinx.coroutines.delay(100) } == null) {
            notified = notifyTap(svc)
            DKLog.d("मिटाना: Allow खिड़की ${OPEN_MS / 1000}s में नहीं खुली - सूचना भेजी ($notified)")
        }
        val yes = withTimeoutOrNull(ASK_MS) { wait.await() } ?: false
        val wasOpened = opened
        pending = null; sender = null
        cancelTap(svc)
        if (!yes) return denied(wasOpened, notified)
        // सच में ट्रैश में गई? (Android कभी-कभी OK देकर भी कुछ नहीं करता)
        val left = withContext(Dispatchers.IO) { uris.count { stillThere(svc, it) } }
        DKLog.d("मिटाना: ${uris.size} में से ${uris.size - left} ट्रैश में (बिना पूछे: $silent)")
        val tip = if (!silent && Build.VERSION.SDK_INT >= 31) " हर बार Allow पूछे बिना मिटाने के लिए: सेटिंग्स → ऐप्स → खास ऐक्सेस → मीडिया मैनेजमेंट → DK चालू करें।" else ""
        return when {
            left == 0 -> "DELETED: $what मिटा दिए (ट्रैश में - 30 दिन तक गैलरी से वापस ला सकते हैं)।$tip"
            left < uris.size -> "DELETE_UNVERIFIED: ${uris.size} में से ${uris.size - left} ही मिटीं, $left वैसी ही हैं।"
            else -> "DELETE_UNVERIFIED: Allow के बाद भी फ़ोटो वहीं है - मिटना पक्का नहीं हुआ।"
        }
    }

    /** खिड़की खुली ही नहीं (पॉप-अप रोका) बनाम खुली पर Allow नहीं - सच वाला संदेश */
    internal fun denied(opened: Boolean, notified: Boolean): String = when {
        opened -> "ERROR_DENIED: आपने Allow नहीं दबाया - कुछ नहीं मिटाया।"
        notified -> "ERROR_POPUP_BLOCKED: फ़ोन ने DK की Allow वाली खिड़की नहीं खोलने दी, और सूचना पर भी टैप नहीं हुआ - कुछ नहीं मिटाया। " + POPUP_TIP
        else -> "ERROR_POPUP_BLOCKED: फ़ोन ने DK की Allow वाली खिड़की नहीं खोलने दी - कुछ नहीं मिटाया। " + POPUP_TIP
    }

    internal const val POPUP_TIP = "सेटिंग्स → ऐप्स → DK → अन्य परमिशन → \"पीछे से चलते समय पॉप-अप विंडो दिखाएँ\" (Display pop-up windows while running in the background) चालू करें, या \"मीडिया मैनेजमेंट\" (खास ऐक्सेस) चालू करें ताकि Allow पूछे बिना मिटे।"
    private const val OPEN_MS = 3_500L
    private const val NOTE_ID = 7431
    private const val PARTIAL = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    @Volatile internal var opened = false

    private fun allFiles(): Boolean = Build.VERSION.SDK_INT >= 30 && try { android.os.Environment.isExternalStorageManager() } catch (_: Exception) { false }

    /** खिड़की न खुले तो: सूचना, टैप करते ही वही Allow खिड़की (StatusNotify वाला चैनल) */
    private fun notifyTap(svc: DKAccessibilityService): Boolean = try {
        val nm = svc.getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(android.app.NotificationChannel(CH, "DK फ़ोटो मिटाना", android.app.NotificationManager.IMPORTANCE_HIGH))
        val pi = android.app.PendingIntent.getActivity(svc, NOTE_ID,
            Intent(svc, DeleteMediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(svc, CH)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle("फ़ोटो मिटाने के लिए टैप करें")
            .setContentText("फ़ोटो मिटाने के लिए टैप करके Allow करें")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pi).build()
        nm.notify(NOTE_ID, n); true
    } catch (e: Exception) { DKLog.d("मिटाना: सूचना नहीं गई ${e.javaClass.simpleName}"); false }

    private fun cancelTap(svc: DKAccessibilityService) {
        try { svc.getSystemService(android.app.NotificationManager::class.java).cancel(NOTE_ID) } catch (_: Exception) {}
    }
    private const val CH = "dk_media_delete"

    /** Android 12+: "मीडिया मैनेजमेंट" की इजाज़त = मिटाने पर Allow नहीं पूछा जाता */
    fun canManage(c: android.content.Context): Boolean = Build.VERSION.SDK_INT >= 31 && try { MediaStore.canManageMedia(c) } catch (_: Exception) { false }

    internal fun done(ok: Boolean) { pending?.complete(ok) }

    private fun pick(svc: DKAccessibilityService, shot: Boolean, n: Int, range: LongRange?, oldest: Boolean): List<Uri> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<Uri>()
        val parts = ArrayList<String>(); val args = ArrayList<String>()
        if (shot) { parts += "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"; args += "%Screenshot%"; args += "Screenshot%" }
        if (range != null) { parts += "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?"; args += range.first.toString(); args += (range.last + 1).toString() }
        val sel = parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        try {
            svc.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), sel, args.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} ${if (oldest) "ASC" else "DESC"}")?.use { c ->
                while (out.size < n && c.moveToNext()) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
            }
        } catch (e: Exception) { DKLog.d("मिटाना: फ़ोटो सूची नहीं पढ़ी ${e.javaClass.simpleName}") }
        return out
    }

    /**
     * ट्रैश में नहीं गई / अब भी आम फ़ोटो है?
     * नवंबर 2026 (मालिक: "फ़ोटो डिलीट नहीं हो रही"): ID वाली query में Android ट्रैश वाली फ़ोटो भी लौटा देता है -
     * इसलिए ट्रैश हो चुकी फ़ोटो को भी \"वहीं है\" गिनते थे. अब IS_TRASHED पढ़ते हैं: पंक्ति नहीं = मिट गई, 1 = ट्रैश में.
     */
    private fun stillThere(svc: DKAccessibilityService, u: Uri): Boolean = try {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        svc.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
            if (!c.moveToFirst()) false else trashLeft(c.getInt(1))
        } ?: true
    } catch (_: Exception) { true }                 // पढ़ नहीं पाए = पक्का नहीं, "मिटा दी" नहीं कहेंगे

    /** IS_TRASHED: 1 = ट्रैश में (मिटी), 0 = अब भी आम फ़ोटो */
    internal fun trashLeft(isTrashed: Int): Boolean = isTrashed != 1
}

/** पारदर्शी, बिना UI: Android की "Allow / Deny" खिड़की खोलो, जवाब MediaDelete को दो */
class DeleteMediaActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = MediaDelete.sender
        if (s == null) { finish(); return }
        MediaDelete.opened = true
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
