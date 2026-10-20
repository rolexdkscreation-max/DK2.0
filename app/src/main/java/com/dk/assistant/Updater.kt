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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App ke andar se update:
 *  GitHub Releases (rolexdkscreation-max/DK2.0) me naya version dekho -> APK download -> Android installer.
 *  Har GitHub build ka tag "v<versionCode>" hota hai, isse pata chalta hai ki naya hai ya nahi.
 */
object Updater {
    const val REPO = "rolexdkscreation-max/DK2.0"

    data class Release(val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long, val pageUrl: String)

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

    /** GitHub se sabse naya release. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DK-Assistant")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) throw java.io.IOException("GitHub ${r.code}")
            val o = JSONObject(r.body?.string() ?: "{}")
            val tag = o.optString("tag_name")
            val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return@withContext null
            val assets = o.optJSONArray("assets")
            var url = ""; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url"); size = a.optLong("size"); break
                }
            }
            if (url.isBlank()) return@withContext null
            Release(code, o.optString("name", tag), o.optString("body", "").trim(), url, size, o.optString("html_url"))
        }
    }

    /** Naya update hai? (null = nahi hai) */
    suspend fun check(c: Context): Release? {
        val rel = latest() ?: return null
        Prefs.edit(c) {
            putLong("upd_checked", System.currentTimeMillis())
            putInt("upd_latest", rel.code)
        }
        return if (rel.code > currentCode(c)) rel else null
    }

    fun canInstall(c: Context) =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** APK download karke seedha Android installer me bhejo. progress = 0..100 */
    suspend fun downloadAndInstall(c: Context, rel: Release, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "DK-Assistant").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Download fail ${r.code}")
                val body = r.body ?: throw java.io.IOException("Khali download")
                val total = body.contentLength().takeIf { it > 0 } ?: rel.size
                session.openWrite("dk.apk", 0, total).use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val p = if (total > 0) (done * 100 / total).toInt() else 0
                            if (p != last) { last = p; withContext(Dispatchers.Main) { progress(p) } }
                        }
                    }
                    session.fsync(out)
                }
            }
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
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(c, "अपडेट हो गया", Toast.LENGTH_LONG).show()
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                DKLog.d("Install fail $status $msg")
                val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signature", true))
                    "पुराना DK अलग key वाला है — एक बार पुराना हटाकर नया इंस्टॉल करें। उसके बाद अपडेट अपने-आप होंगे।"
                else "अपडेट नहीं हुआ: $msg"
                Toast.makeText(c, hint, Toast.LENGTH_LONG).show()
            }
        }
    }
}
