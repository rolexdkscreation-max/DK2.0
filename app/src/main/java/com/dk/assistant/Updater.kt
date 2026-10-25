package com.dk.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App ke andar se update (v3.1):
 *  GitHub Releases (REPO = is app ka apna release repo) me naya version dekho -> background me APK download -> "इंस्टॉल करें".
 *  Har build ka tag "v<versionCode>". Release ke text me GitHub workflow likhta hai:
 *     TYPE: software | security      (UI badla = सॉफ़्टवेयर अपडेट, sirf sudhaar = सिक्योरिटी पैच)
 *     VERSION: 3.1                   (dikhane wala version, build number nahi)
 *     ---                            (iske neeche "क्या नया है")
 *  Download ek baar ho gaya to file rakhi rehti hai -> button "इंस्टॉल करें" (dobara download nahi).
 *  Download wake-lock ke saath chalta hai: screen band ho ya DK band karo, tab bhi poora hota hai.
 */
object Updater {
    /** Sirf update ka source (UI me kahin nahi dikhta). Coding agent ka repo alag hai: Prefs.ghRepo (default khaali) */
    const val REPO = "rolexdkscreation-max/DK2.0"
    private const val CH = "dk_update"
    private const val NOTIF_ID = 4201

    data class Release(
        val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long, val pageUrl: String,
        /** "software" ya "security" */
        val type: String = "software",
        /** Dikhane wala version, jaise "3.1" ya "3.1.2" */
        val version: String = "",
    ) {
        val isSecurity get() = type == "security"
        /** "सॉफ़्टवेयर अपडेट" / "सिक्योरिटी पैच" */
        val typeLabel get() = if (isSecurity) "सिक्योरिटी पैच" else "सॉफ़्टवेयर अपडेट"
        val sizeMb get() = String.format(java.util.Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun currentCode(c: Context): Int {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    fun currentName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    /** Sirf version ("3.1") - build number nahi */
    fun displayVersion(c: Context): String = "DK " + currentName(c)

    private fun parse(o: JSONObject): Release? {
        val tag = o.optString("tag_name")
        val assets = o.optJSONArray("assets")
        var url = ""; var size = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
        }
        return build(tag, o.optString("name", tag), o.optString("body", ""), url, size, o.optString("html_url"))
    }

    /** API और feed दोनों से एक ही तरह का Release (TYPE:/VERSION: लाइनें, notes) */
    private fun build(tag: String, name: String, rawBody: String, url: String, size: Long, page: String): Release? {
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return null
        if (url.isBlank()) return null
        val body = rawBody.replace("\r", "")
        val lines = body.lines().map { it.trim() }
        val type = lines.firstOrNull { it.startsWith("TYPE:") }?.substringAfter(":")?.trim()?.lowercase()
            ?.let { if (it.startsWith("sec")) "security" else "software" } ?: "software"
        val title = name.ifBlank { tag }
        val ver = lines.firstOrNull { it.startsWith("VERSION:") }?.substringAfter(":")?.trim()
            ?: title.removePrefix("DK").trim()
        val notes = if (body.contains("\n---\n")) body.substringAfter("\n---\n").trim()
            else lines.filterNot { it.startsWith("TYPE:") || it.startsWith("VERSION:") }.joinToString("\n").trim()
        return Release(code, title, notes, url, size, page, type, ver)
    }

    /** GitHub का HTTP जवाब ठीक नहीं (403 = घंटे की सीमा, 429, 5xx...) - साफ़ हिंदी वजह के साथ */
    class GhHttp(val code: Int) : java.io.IOException(ReleaseFeed.friendly(code))

    private fun getJson(url: String, token: String = ""): JSONObject? {
        val rb = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "DK-Assistant")
        if (token.isNotBlank()) rb.header("Authorization", "Bearer $token")
        http.newCall(rb.build()).execute().use { r ->
            if (r.code == 404) return null
            if (r.code == 401 && token.isNotBlank()) return getJson(url, "")     // token गलत/पुराना - बिना token
            if (!r.isSuccessful) throw GhHttp(r.code)
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    /** API न मिले तो: github.com का releases.atom feed + सीधा APK पता (API की घंटे वाली सीमा नहीं) */
    private fun fromFeed(): Release? {
        val req = Request.Builder().url(ReleaseFeed.feedUrl(REPO)).header("User-Agent", "DK-Assistant").build()
        val e = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw GhHttp(r.code)
            ReleaseFeed.parseAtom(r.body?.string().orEmpty())
        } ?: return null
        val url = ReleaseFeed.apkUrl(REPO, e.tag)
        // APK का आकार (download पूरा हुआ या नहीं, यह जाँचने के लिए). न मिले तो 0 = आकार की जाँच नहीं
        val size = try {
            http.newCall(Request.Builder().url(url).head().header("User-Agent", "DK-Assistant").build()).execute().use { r ->
                if (r.code == 404) return null          // इस release में अभी APK नहीं (build चल रहा)
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
            }
        } catch (_: Exception) { 0L }
        return build(e.tag, e.title, e.body, url, size, e.page)
    }

    /** GitHub se sabse naya release. token = Settings वाला GitHub token (हो तो घंटे में 5000 बार) */
    suspend fun latest(token: String = ""): Release? = withContext(Dispatchers.IO) {
        try {
            getJson("https://api.github.com/repos/$REPO/releases/latest", token)?.let { parse(it) }
        } catch (e: GhHttp) {
            if (!ReleaseFeed.shouldFallback(e.code)) throw e
            try { fromFeed() } catch (_: Exception) { throw e }
        } catch (e: java.io.IOException) {
            // API तक नहीं पहुँचा (DNS/नेटवर्क) - github.com शायद मिल जाए
            try { fromFeed() } catch (_: Exception) { throw java.io.IOException(ReleaseFeed.friendly(0), e) }
        }
    }

    /** Kisi khaas build ka release (update ke baad "क्या नया है" dikhane ke liye) */
    suspend fun byCode(code: Int): Release? = withContext(Dispatchers.IO) {
        try { getJson("https://api.github.com/repos/$REPO/releases/tags/v$code")?.let { parse(it) } } catch (_: Exception) { null }
    }

    /** Naya update hai? (null = nahi hai) */
    suspend fun check(c: Context): Release? {
        val rel = latest(Prefs.ghToken(c)) ?: return null
        Prefs.edit(c) {
            putLong("upd_checked", System.currentTimeMillis())
            putInt("upd_latest", rel.code)
            putString("upd_type", rel.type)
            putString("upd_version", rel.version)
            putString("upd_notes_${rel.code}", rel.notes.take(3000))
            // poora release yaad rakho: bina internet ke bhi chip/download/install sahi chale
            putString("upd_url", rel.apkUrl); putLong("upd_size", rel.size); putString("upd_name", rel.name)
        }
        return if (rel.code > currentCode(c)) rel else null
    }

    /** Pichhli baar mila naya update (internet ke bina). null = koi naya nahi */
    fun cached(c: Context): Release? {
        val sp = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        val code = sp.getInt("upd_latest", 0)
        val url = sp.getString("upd_url", "").orEmpty()
        if (code <= currentCode(c) || url.isBlank()) return null
        return Release(code, sp.getString("upd_name", "").orEmpty(), sp.getString("upd_notes_$code", "").orEmpty(), url,
            sp.getLong("upd_size", 0L), "", sp.getString("upd_type", "software") ?: "software", sp.getString("upd_version", "").orEmpty())
    }

    fun canInstall(c: Context) =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ================================================================ background download
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** Abhi kis build ka download chal raha hai (0 = koi nahi) */
    @Volatile var downloadingCode = 0
        private set
    /** 0..100 */
    @Volatile var progress = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private fun dir(c: Context) = java.io.File(c.filesDir, "updates").apply { mkdirs() }
    private fun apkFile(c: Context, code: Int) = java.io.File(dir(c), "DK-v$code.apk")
    private fun partFile(c: Context, code: Int) = java.io.File(dir(c), "DK-v$code.part")

    /** Poora download ho chuka APK (warna null) */
    fun downloaded(c: Context, rel: Release): java.io.File? =
        apkFile(c, rel.code).takeIf { it.exists() && (rel.size <= 0 || it.length() == rel.size) }

    fun isDownloading(rel: Release) = downloadingCode == rel.code && job?.isActive == true

    /** Purane/install ho chuke updates ki files hatao */
    fun cleanup(c: Context) {
        val cur = currentCode(c)
        dir(c).listFiles()?.forEach { f ->
            val code = f.name.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (code <= cur) f.delete()
        }
    }

    /**
     * Background me download shuru (pehle se chal raha/ho chuka ho to kuch nahi).
     * Beech me toota to agli baar wahin se aage (Range).
     */
    @Synchronized
    fun startDownload(c0: Context, rel: Release) {
        val c = c0.applicationContext
        if (downloaded(c, rel) != null || isDownloading(rel)) return
        if (job?.isActive == true) job?.cancel()          // purane version ka download chal raha tha -> naya hi lo
        // purane (chhode hue) version ki files hatao - jagah na bhare
        dir(c).listFiles()?.forEach { f -> if (!f.name.startsWith("DK-v${rel.code}.")) f.delete() }
        lastError = null; progress = 0; downloadingCode = rel.code
        job = scope.launch {
            val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DK:update").apply { setReferenceCounted(false); acquire(15 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            val wifi = (c.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DK:update")?.apply { setReferenceCounted(false); acquire() }
            try {
                notifyProgress(c, rel, 0)
                val part = partFile(c, rel.code)
                var have = if (part.exists()) part.length() else 0L
                if (rel.size > 0 && have >= rel.size) { part.delete(); have = 0 }
                val rb = Request.Builder().url(rel.apkUrl).header("User-Agent", "DK-Assistant")
                if (have > 0) rb.header("Range", "bytes=$have-")
                http.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("Download fail ${r.code}")
                    val body = r.body ?: throw java.io.IOException("Khali download")
                    if (r.code != 206) have = 0                       // server ne aage se nahi diya -> shuru se
                    val total = if (rel.size > 0) rel.size else have + body.contentLength()
                    java.io.FileOutputStream(part, have > 0).use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var done = have; var last = -1; var lastNotif = 0L
                            while (isActive) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                                if (p != last) {
                                    last = p; progress = p
                                    val now = System.currentTimeMillis()
                                    if (now - lastNotif > 700) { lastNotif = now; notifyProgress(c, rel, p) }
                                }
                            }
                        }
                    }
                }
                if (!isActive) return@launch
                if (rel.size > 0 && part.length() != rel.size) throw java.io.IOException("अधूरा download (${part.length()}/${rel.size})")
                val apk = apkFile(c, rel.code)
                apk.delete(); part.renameTo(apk)
                progress = 100
                DKLog.d("Update v${rel.code} download पूरा")
                notifyReady(c, rel)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { clearNotification(c); throw e }
                lastError = e.message ?: e.javaClass.simpleName
                DKLog.d("Update download fail: $lastError")
                notifyFail(c, rel)
            } finally {
                if (downloadingCode == rel.code) downloadingCode = 0
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
                try { wifi?.release() } catch (_: Exception) {}
            }
        }
    }

    /** Download ho chuki file ko Android installer me bhejo */
    suspend fun install(c: Context, rel: Release) = withContext(Dispatchers.IO) {
        val apk = downloaded(c, rel) ?: throw java.io.IOException("पहले डाउनलोड करें")
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("dk.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            // update ke baad "क्या नया है" dikhane ke liye yaad rakho
            Prefs.edit(c) { putInt("upd_from_code", currentCode(c)) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    // ---------------------------------------------------------------- notifications
    private fun nm(c: Context): android.app.NotificationManager {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(android.app.NotificationChannel(CH, "DK अपडेट", android.app.NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun openUpdate(c: Context): PendingIntent = PendingIntent.getActivity(c, NOTIF_ID,
        Intent(c, SettingsActivity::class.java).putExtra("section", "update").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(c: Context) =
        (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else @Suppress("DEPRECATION") android.app.Notification.Builder(c))
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(openUpdate(c))

    private fun notifyProgress(c: Context, rel: Release, p: Int) = try {
        nm(c).notify(NOTIF_ID, builder(c)
            .setContentTitle(UiLang.s("${rel.typeLabel} डाउनलोड हो रहा है • DK ${rel.version}"))
            .setContentText(UiLang.s("$p% • ${rel.sizeMb} — बैकग्राउंड में चल रहा है"))
            .setProgress(100, p, p == 0).setOngoing(true).setOnlyAlertOnce(true).build())
    } catch (_: Exception) {}

    private fun notifyReady(c: Context, rel: Release) = try {
        nm(c).notify(NOTIF_ID, builder(c).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(UiLang.s("${rel.typeLabel} तैयार • DK ${rel.version}"))
            .setContentText(UiLang.s("डाउनलोड हो गया — इंस्टॉल करने के लिए टैप करें"))
            .setAutoCancel(true).build())
    } catch (_: Exception) {}

    private fun notifyFail(c: Context, rel: Release) = try {
        nm(c).notify(NOTIF_ID, builder(c).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(UiLang.s("अपडेट डाउनलोड रुक गया • DK ${rel.version}"))
            .setContentText(UiLang.s("इंटरनेट आने पर टैप करें — जहाँ रुका था वहीं से आगे होगा"))
            .setAutoCancel(true).build())
    } catch (_: Exception) {}

    fun clearNotification(c: Context) = try { nm(c).cancel(NOTIF_ID) } catch (_: Exception) {}
}

/** Android installer ka jawab: "Install" dabane wali screen dikhao, ya nateeja batao. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> UiLang.toast(c, "अपडेट हो गया", Toast.LENGTH_LONG).show()
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                DKLog.d("Install fail $status $msg")
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signature", true))
                    "पुराना DK अलग key वाला है — एक बार पुराना हटाकर नया इंस्टॉल करें। उसके बाद अपडेट अपने-आप होंगे।"
                else "अपडेट नहीं हुआ: $msg"
                UiLang.toast(c, hint, Toast.LENGTH_LONG).show()
            }
        }
    }
}
