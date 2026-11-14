package com.dk.assistant.dkai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dk.assistant.DKLog
import com.dk.assistant.R
import com.dk.assistant.studio.StudioCore
import com.dk.assistant.studio.StudioProject
import com.dk.assistant.studio.StudioSession
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): DKAI Terminal की `apk` कमांड - फ़ोन पर ही असली, इंस्टॉल होने वाला ऐप.
 *
 *   apk make <क्या बनाना है>   AI (App Studio) ऐप लिखे -> APK बने -> इंस्टॉल का बटन अपने-आप
 *   apk build [फ़ोल्डर] [नाम]  index.html वाले फ़ोल्डर से APK (+ इंस्टॉल)
 *   apk install <file.apk>      इंस्टॉल की स्क्रीन खोलो
 *   apk list                    बने हुए APK
 *
 * असली काम (manifest, zip, sign) [DkaiApkCore] में. यहाँ: साँचा assets से, इंस्टॉल, notification, App Studio से जोड़.
 */
object DkaiApk {
    const val ASSET = "dk_apkshell.apk"
    private const val CH = "dk_apk_build"
    private const val NOTIF = 7714

    fun outDir(c: Context): File = File(DkaiWorkspace.workspace(c), "apks").apply { mkdirs() }
    private fun keyDir(c: Context): File = File(c.filesDir, "dk_apps_key")      // Workspace के बाहर - Terminal से नहीं दिखती

    /** assets का साँचा -> cache (हर बार ताज़ा: DK अपडेट हो तो साँचा भी नया) */
    private fun template(c: Context): File {
        val f = File(c.cacheDir, ASSET)
        c.assets.open(ASSET).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** [dir] (या उसका preview/ / www/) -> साइन किया APK. नाम खाली = फ़ोल्डर का नाम */
    fun build(c: Context, dir: File, name: String): DkaiApkCore.Built {
        val web = DkaiApkCore.webRoot(dir) ?: throw IllegalArgumentException("इस फ़ोल्डर में index.html नहीं (${dir.name})")
        val n = name.ifBlank { StudioProject(dir).title.takeIf { File(dir, ".studio").isFile && it.isNotBlank() } ?: dir.name }
        val b = DkaiApkCore.build(template(c), web, n, outDir(c), keyDir(c), c.cacheDir)
        DKLog.d("apk: बना ${b.apk.name} (${b.pkg}, ${b.files} फ़ाइल, ${b.apk.length() / 1024} KB)")
        return b
    }

    fun canInstall(c: Context): Boolean = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    private fun installIntent(c: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** इंस्टॉल की स्क्रीन (Android का अपना \"इंस्टॉल करें\" बटन) + notification में भी बटन (पीछे से स्क्रीन न खुल पाए तब) */
    fun install(c: Context, apk: File, label: String = apk.nameWithoutExtension): String {
        if (!apk.isFile) return "apk: ${apk.name} नहीं मिली\n"
        if (!canInstall(c)) {
            try {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            notify(c, apk, label, "पहले DK को ऐप इंस्टॉल करने की इजाज़त दें, फिर यहाँ टैप करें")
            return "इजाज़त चाहिए: Settings में \"इस स्रोत से इंस्टॉल की अनुमति\" (DK) चालू करें, फिर notification पर \"इंस्टॉल करें\" दबाएँ\n" +
                "या लिखें: apk install apks/${apk.name}\n"
        }
        notify(c, apk, label, "टैप करें = इंस्टॉल करें")
        return try { c.startActivity(installIntent(c, apk)); "📲 इंस्टॉल की स्क्रीन खुल गई - \"इंस्टॉल करें\" दबाएँ\n" }
        catch (e: Exception) { "इंस्टॉल की स्क्रीन नहीं खुली (${e.message}) - notification से इंस्टॉल करें\n" }
    }

    private fun notify(c: Context, apk: File, label: String, text: String) = try {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "DK - बने हुए ऐप", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, NOTIF, if (canInstall(c)) installIntent(c, apk)
            else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c)
        nm.notify(NOTIF, b.setSmallIcon(R.drawable.ic_dk).setContentTitle("ऐप तैयार: $label").setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build())
    } catch (e: Exception) { DKLog.d("apk: notification नहीं: ${e.message}") }

    // ------------------------------------------------------------------ App Studio से: लिखो -> बनाओ -> इंस्टॉल
    /** `apk make` का बना project पूरा होते ही APK. (Terminal / DK लाइव दोनों को नतीजा [DkaiShell.listeners] से) */
    fun make(c: Context, want: String): String {
        val ctx = c.applicationContext
        val text = want.trim().trim('"', '\'')
        if (text.length < 3) return "लिखें: apk make कैलकुलेटर ऐप\n"
        val kind = StudioCore.kindOf(text).let { if (it.native || it == StudioCore.Kind.PWA) StudioCore.Kind.WEB else it }
        val err = StudioSession.start(ctx, text, StudioSession.WHO_COMPUTER, openScreen = false, kind = kind,
            after = { p, ok -> afterStudio(ctx, p, ok) })
        if (err != null) return "apk make: शुरू नहीं हुआ - $err\n"
        return "🛠 ऐप बन रहा है: \"${text.take(80)}\" (App Studio का AI code लिख रहा है)\n" +
            "पूरा होते ही अपने-आप APK बनेगा और इंस्टॉल की स्क्रीन खुलेगी. बीच की हालत: DKAI होम / App Studio.\n"
    }

    private fun afterStudio(c: Context, p: StudioProject, ok: Boolean) {
        val say: String = if (!ok) "⚠ ऐप का code नहीं बना: ${StudioSession.lastResult.lineSequence().first().take(160)}\n" else try {
            val b = build(c, p.dir, DkaiApkCore.appName(p.request.ifBlank { p.title }))
            "✅ APK तैयार: apks/${b.apk.name} (${maxOf(1, b.apk.length() / 1024)} KB, ${b.files} फ़ाइल, ${b.pkg})\n" + install(c, b.apk, b.label)
        } catch (e: Exception) { "⚠ APK नहीं बना: ${e.message}\n" }
        DkaiShell.listeners.forEach { l -> try { l(say) } catch (_: Exception) {} }
        DKLog.d("apk make: " + say.lineSequence().first())
    }

    // ------------------------------------------------------------------ Terminal की कमांड
    fun command(c: Context, arg: String, cwd: File): String {
        val w = DkaiTerm.words(arg)
        val sub = w.firstOrNull()?.lowercase().orEmpty()
        val rest = w.drop(1)
        return try {
            when (sub) {
                "", "help", "-h", "--help" -> DkaiTerm.APK_HELP
                "make", "new", "create" -> make(c, arg.trim().substringAfter(' ', ""))
                "build" -> {
                    val dir = rest.firstOrNull()?.let { File(cwd, it).canonicalFile } ?: cwd
                    if (!dir.isDirectory || DkaiWorkspace.rootOf(c, dir) == null) return "apk build: फ़ोल्डर नहीं मिला (या ऐप के फ़ोल्डर से बाहर)\n"
                    if (DkaiApkCore.webRoot(dir) == null) return "apk build: ${dir.name} में index.html नहीं. पहले index.html बनाएँ, या लिखें: apk make कैलकुलेटर ऐप\n"
                    val b = build(c, dir, rest.drop(1).joinToString(" "))
                    "✅ APK तैयार: apks/${b.apk.name} (${maxOf(1, b.apk.length() / 1024)} KB, ${b.files} फ़ाइल)\nऐप: ${b.label} • package ${b.pkg}\n" + install(c, b.apk, b.label)
                }
                "install" -> {
                    val f = rest.firstOrNull()?.let { File(cwd, it).canonicalFile.takeIf { x -> x.isFile } ?: File(outDir(c), it) }
                        ?: return "लिखें: apk install apks/नाम.apk\n"
                    if (!f.isFile || !f.name.endsWith(".apk") || DkaiWorkspace.rootOf(c, f) == null) return "apk install: ${f.name} नहीं मिली\n"
                    install(c, f)
                }
                "list", "ls" -> outDir(c).listFiles { f -> f.name.endsWith(".apk") }.orEmpty().sortedByDescending { it.lastModified() }
                    .joinToString("") { "${it.name}  ${maxOf(1, it.length() / 1024)} KB\n" }.ifBlank { "अभी कोई APK नहीं बना. लिखें: apk make कैलकुलेटर ऐप\n" }
                else -> "apk: '$sub' नहीं पहचाना\n" + DkaiTerm.APK_HELP
            }
        } catch (e: Exception) { "apk: नहीं हुआ - ${e.message ?: e.javaClass.simpleName}\n" }
    }
}
