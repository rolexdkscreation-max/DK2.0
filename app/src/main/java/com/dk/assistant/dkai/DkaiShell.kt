package com.dk.assistant.dkai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.dk.assistant.DKLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DKAI Terminal का एक ही सत्र - Terminal स्क्रीन और DK लाइव (tool "terminal") दोनों यहीं से चलाते हैं:
 * वही फ़ोल्डर (cwd), वही इतिहास, वही alias / variable (फ़ोन में सेव - ऐप बंद होकर भी याद).
 * DK लाइव कोई कमांड चलाए तो Terminal स्क्रीन पर भी दिखती है ([listeners]).
 * नियम [DkaiTerm] में. सब कुछ ऐप के अपने फ़ोल्डर में - बाहर नहीं, root नहीं.
 */
object DkaiShell {
    /** Terminal स्क्रीन: DK लाइव की चलाई कमांड + उसका output */
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile var cwd: File? = null
    @Volatile private var prev: File? = null
    val env = LinkedHashMap<String, String>()
    val aliases = LinkedHashMap<String, String>(DkaiTerm.DEFAULT_ALIASES)
    val history = CopyOnWriteArrayList<String>()
    @Volatile private var loaded = false
    /** DK लाइव की खतरनाक कमांड जो मालिक की "हाँ" का इंतज़ार कर रही है ([DkaiTerm.liveGate]) */
    @Volatile private var pending: DkaiTerm.Pending? = null

    const val MAX_HISTORY = 300
    const val LIVE_TIMEOUT_MS = 30_000L
    const val MAX_DOWNLOAD = 60L * 1024 * 1024

    fun home(c: Context): File = DkaiWorkspace.workspace(c)
    fun dir(c: Context): File = (cwd?.takeIf { it.isDirectory && DkaiWorkspace.rootOf(c, it) != null } ?: home(c)).also { cwd = it }

    private fun store(c: Context) = File(c.filesDir, "dkai_term.json")

    @Synchronized fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = store(c); if (!f.isFile) return
            val o = JSONObject(f.readText())
            o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) history.add(a.getString(i)) }
            o.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.getString(k) } }
            o.optJSONObject("alias")?.let { e -> e.keys().forEach { k -> aliases[k] = e.getString(k) } }
            o.optString("cwd").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory && DkaiWorkspace.rootOf(c, it) != null }?.let { cwd = it }
        } catch (e: Exception) { DKLog.d("terminal: सत्र नहीं पढ़ा: ${e.message}") }
    }

    @Synchronized fun save(c: Context) {
        try {
            store(c).writeText(JSONObject()
                .put("history", JSONArray(history.takeLast(MAX_HISTORY)))
                .put("env", JSONObject(env as Map<*, *>))
                .put("alias", JSONObject(aliases as Map<*, *>))
                .put("cwd", cwd?.absolutePath.orEmpty()).toString())
        } catch (_: Exception) {}
    }

    fun remember(c: Context, cmd: String) {
        load(c)
        if (cmd.isNotBlank() && history.lastOrNull() != cmd) history.add(cmd)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        save(c)
    }

    fun prompt(c: Context): String = DkaiCore.prompt(dir(c), home(c))

    /** कमांड तैयार: !! / !n और alias. null = इतिहास में नहीं */
    fun prepare(c: Context, raw: String): String? {
        load(c)
        val h = DkaiTerm.expandHistory(raw, history) ?: return null
        return DkaiTerm.expandAlias(h, aliases)
    }

    // ------------------------------------------------------------ अपनी कमांड (Terminal स्क्रीन और DK लाइव दोनों)
    /** null = यह अपनी कमांड नहीं (sh चलाए). नहीं तो उसका output */
    fun builtin(c: Context, cmd: String): String? {
        val t = cmd.trim(); val h = DkaiTerm.head(t); val arg = t.removePrefix(h).trim()
        val d = dir(c)
        return when (h) {
            "help", "dkhelp" -> DkaiTerm.HELP
            "cd" -> {
                val to = if (arg == "-") prev ?: d else DkaiCore.cd(t, d, home(c)) ?: d
                if (to.isDirectory && DkaiWorkspace.rootOf(c, to) != null) { prev = d; cwd = to; save(c); "" }
                else "cd: $arg: यह फ़ोल्डर नहीं (या ऐप के फ़ोल्डर से बाहर)\n"
            }
            "export" -> if (arg.isBlank()) envText() else { val p = DkaiTerm.pairs(arg); env.putAll(p); save(c); if (p.isEmpty()) "export: लिखें A=1\n" else "" }
            "unset" -> { arg.split(Regex("\\s+")).forEach { env.remove(it) }; save(c); "" }
            "env" -> envText()
            "alias" -> if (arg.isBlank()) aliases.entries.joinToString("") { "alias ${it.key}='${it.value}'\n" }
                else DkaiTerm.aliasDef(arg)?.let { (k, v) -> aliases[k] = v; save(c); "" } ?: "alias: लिखें ll='ls -la'\n"
            "unalias" -> { arg.split(Regex("\\s+")).forEach { aliases.remove(it) }; save(c); "" }
            "history" -> if (arg == "-c") { history.clear(); save(c); "इतिहास साफ़\n" }
                else history.takeLast(50).let { l -> val start = history.size - l.size; l.mapIndexed { i, s -> "%4d  %s\n".format(start + i + 1, s) }.joinToString("") }
            "tree" -> { val f = if (arg.isBlank()) d else File(d, arg).canonicalFile
                if (f.isDirectory && DkaiWorkspace.rootOf(c, f) != null) DkaiTerm.tree(f) else "tree: फ़ोल्डर नहीं मिला\n" }
            "sysinfo" -> sysinfo(c)
            "wget", "curl" -> fetch(c, t, d)
            "unzip" -> unzip(c, arg, d)
            "zip" -> zip(c, arg, d)
            // 14 नवंबर 2026: फ़ोन पर ही असली ऐप (APK) - make / build / install / list
            "apk" -> DkaiApk.command(c, arg, d)
            else -> null
        }
    }

    private fun envText(): String = (mapOf("HOME" to "~ (Workspace)", "SHELL" to "/system/bin/sh") + env).entries.joinToString("") { "${it.key}=${it.value}\n" }

    private fun sysinfo(c: Context): String = buildString {
        append("फ़ोन: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("CPU: ${Runtime.getRuntime().availableProcessors()} core, ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\n")
        try {
            val am = c.getSystemService(ActivityManager::class.java); val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            append("RAM: ${DkaiTerm.size(mi.availMem)} खाली / ${DkaiTerm.size(mi.totalMem)}\n")
        } catch (_: Exception) {}
        try {
            val st = StatFs(c.filesDir.absolutePath)
            append("जगह (फ़ोन): ${DkaiTerm.size(st.availableBytes)} खाली / ${DkaiTerm.size(st.totalBytes)}\n")
        } catch (_: Exception) {}
        try {
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plug = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (lvl >= 0) append("बैटरी: ${lvl * 100 / sc.coerceAtLeast(1)}%${if (plug) " (चार्ज हो रही)" else ""}\n")
        } catch (_: Exception) {}
        append("Workspace: ${DkaiTerm.size(home(c).walkTopDown().filter { it.isFile }.sumOf { it.length() })} इस्तेमाल\n")
    }

    private fun fetch(c: Context, cmd: String, d: File): String {
        val f = DkaiTerm.parseFetch(cmd) ?: return "लिखें: wget https://… या curl https://…\n"
        return try {
            val con = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DKAI-Terminal/1.0")
            }
            val code = con.responseCode
            val stream = if (code >= 400) con.errorStream else con.inputStream
            if (!f.save) {
                val body = stream?.use { s -> String(s.readNBytesCompat(200_000), Charsets.UTF_8) }.orEmpty()
                "HTTP $code\n" + DkaiTerm.clip(DkaiTerm.stripAnsi(body), 20_000) + "\n"
            } else {
                val out = File(d, f.out!!).canonicalFile
                if (DkaiWorkspace.rootOf(c, out) == null) return "wget: ऐप के फ़ोल्डर से बाहर नहीं\n"
                if (code >= 400) return "wget: HTTP $code - डाउनलोड नहीं हुआ\n"
                var n = 0L
                stream.use { s -> out.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) { val r = s.read(buf); if (r < 0) break; n += r; if (n > MAX_DOWNLOAD) break; o.write(buf, 0, r) }
                } }
                if (n > MAX_DOWNLOAD) { out.delete(); "wget: फ़ाइल 60MB से बड़ी - रोका\n" }
                else "सेव: ${out.name} (${DkaiTerm.size(n)})\n"
            }
        } catch (e: Exception) { "${DkaiTerm.head(cmd)}: नहीं हुआ - ${e.message ?: e.javaClass.simpleName}\n" }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < max) { val r = read(buf); if (r < 0) break; out.write(buf, 0, minOf(r, max - out.size())) }
        return out.toByteArray()
    }

    private fun unzip(c: Context, arg: String, d: File): String {
        val w = arg.split(Regex("\\s+")).filter { it.isNotBlank() && it != "-o" }
        val zf = w.firstOrNull()?.let { File(d, it).canonicalFile } ?: return "लिखें: unzip file.zip [फ़ोल्डर]\n"
        if (!zf.isFile) return "unzip: ${zf.name} नहीं मिली\n"
        val dest = (w.getOrNull(1)?.let { File(d, it) } ?: File(d, zf.nameWithoutExtension)).canonicalFile
        if (DkaiWorkspace.rootOf(c, dest) == null) return "unzip: ऐप के फ़ोल्डर से बाहर नहीं\n"
        var n = 0
        return try {
            ZipInputStream(zf.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = DkaiTerm.safeEntry(dest, e.name) ?: continue
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) }; n++ }
                }
            }
            "खोला: $n फ़ाइल -> ${DkaiCore.prompt(dest, home(c))}\n"
        } catch (e: Exception) { "unzip: नहीं हुआ - ${e.message}\n" }
    }

    private fun zip(c: Context, arg: String, d: File): String {
        val w = arg.split(Regex("\\s+")).filter { it.isNotBlank() && it != "-r" }
        if (w.size < 2) return "लिखें: zip out.zip फ़ाइल/फ़ोल्डर…\n"
        val out = File(d, w[0].let { if (it.endsWith(".zip")) it else "$it.zip" }).canonicalFile
        if (DkaiWorkspace.rootOf(c, out) == null) return "zip: ऐप के फ़ोल्डर से बाहर नहीं\n"
        var n = 0
        return try {
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                for (p in w.drop(1)) {
                    val src = File(d, p).canonicalFile
                    if (!src.exists() || DkaiWorkspace.rootOf(c, src) == null) continue
                    val base = src.parentFile ?: d
                    src.walkTopDown().filter { it.isFile && it != out }.forEach { f ->
                        z.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
                    }
                }
            }
            "बनी: ${out.name} ($n फ़ाइल, ${DkaiTerm.size(out.length())})\n"
        } catch (e: Exception) { "zip: नहीं हुआ - ${e.message}\n" }
    }

    // ------------------------------------------------------------ sh
    /** असली shell: sh -c, उसी फ़ोल्डर में, अपने variable के साथ */
    fun start(c: Context, cmd: String, d: File): Process {
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        return ProcessBuilder(sh, "-c", cmd).directory(d).redirectErrorStream(true).apply {
            val e = environment()
            e["HOME"] = home(c).absolutePath; e["TMPDIR"] = c.cacheDir.absolutePath; e["TERM"] = "dumb"
            e["DK_WORKSPACE"] = home(c).absolutePath; e["DK_STUDIO"] = File(c.filesDir, "studio").absolutePath
            e.putAll(env)
        }.start()
    }

    data class Res(val code: Int, val out: String, val timedOut: Boolean)

    /** पूरा चलाओ और output लौटाओ (DK लाइव / बिना स्क्रीन). [timeoutMs] के बाद रोको */
    fun runAll(c: Context, raw: String, timeoutMs: Long = LIVE_TIMEOUT_MS): Res {
        val cmd = prepare(c, raw) ?: return Res(1, "इतिहास में ऐसी कमांड नहीं\n", false)
        remember(c, cmd)
        val h = DkaiTerm.head(cmd)
        if (h == "clear") return Res(0, "", false)
        if (h == "open" || h == "preview") return Res(1, "$h सिर्फ़ Terminal स्क्रीन पर चलती है (DKAI होम -> Terminal)\n", false)
        builtin(c, cmd)?.let { return Res(if (it.contains(": नहीं") || it.startsWith("cd:")) 1 else 0, it, false) }
        val p = try { start(c, cmd, dir(c)) } catch (e: Exception) { return Res(127, "चल नहीं पाई: ${e.message}\n", false) }
        val sb = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { r -> val ch = CharArray(4096); while (true) { val n = r.read(ch); if (n < 0) break; synchronized(sb) { if (sb.length < 400_000) sb.append(ch, 0, n) } } } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val done = try { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) try { p.destroy() } catch (_: Exception) {}
        reader.join(1_000)
        val out = DkaiTerm.stripAnsi(synchronized(sb) { sb.toString() })
        return Res(if (done) p.exitValue() else 130, out, !done)
    }

    // ------------------------------------------------------------ DK लाइव का tool "terminal"
    suspend fun liveTool(c: Context, args: JSONObject): String = withContext(Dispatchers.IO) {
        load(c)
        val cmd = args.optString("command").trim()
        val ok = args.optBoolean("confirmed", false)
        if (cmd.isBlank() || cmd == "help") return@withContext "TERMINAL INFO: " + DkaiTerm.KNOWLEDGE + "\nNow at: ${prompt(c)}. Explain to the user in short simple Hindi what he wants to know."
        // ऑडिट 13 नवंबर 2026: "हाँ" ऐप खुद मालिक की बात से जाँचता है - AI का confirmed=true अकेले काफ़ी नहीं
        when (DkaiTerm.liveGate(cmd, ok, pending, com.dk.assistant.DKState.lastUserAt, com.dk.assistant.DKState.lastUser, System.currentTimeMillis())) {
            DkaiTerm.Gate.Ask -> {
                pending = DkaiTerm.Pending(cmd, System.currentTimeMillis())
                DKLog.d("terminal (लाइव): पुष्टि माँगी - ${cmd.take(80)}")
                return@withContext "CONFIRM_NEEDED: the command `$cmd` can delete / move / overwrite files or reach outside the DKAI workspace (in ${prompt(c)}). " +
                    "Tell the user in one short Hindi line exactly what it will do and ask हाँ या ना. Only after he himself clearly says yes, call terminal again with the same command and confirmed=true. " +
                    "The app checks his own words - it will not run without his yes."
            }
            DkaiTerm.Gate.NotHeard -> {
                DKLog.d("terminal (लाइव): confirmed=true पर मालिक की हाँ नहीं सुनी - नहीं चलाई: ${cmd.take(80)}")
                return@withContext "NOT_RUN: the app did not hear the user himself say a clear yes after the question, so `$cmd` was NOT run. " +
                    "Ask him again in one short Hindi line (हाँ या ना) and wait for his answer. Never say it ran."
            }
            DkaiTerm.Gate.Run -> pending = null
        }
        val before = prompt(c)
        listeners.forEach { l -> try { l("🎤 DK लाइव: $before \$ $cmd\n") } catch (_: Exception) {} }
        val r = runAll(c, cmd)
        listeners.forEach { l -> try { l(r.out + (if (r.timedOut) "[30 सेकंड - रोका]\n" else if (r.code != 0) "[exit ${r.code}]\n" else "")) } catch (_: Exception) {} }
        DKLog.d("terminal (लाइव): ${cmd.take(80)} -> exit ${r.code}${if (r.timedOut) " (समय खत्म)" else ""}")
        "TERMINAL RESULT (real, from the phone): command `$cmd` in $before -> exit ${r.code}${if (r.timedOut) " (stopped after 30 s)" else ""}, now in ${prompt(c)}.\n" +
            "OUTPUT:\n" + DkaiTerm.clip(r.out.ifBlank { "(कुछ नहीं छपा)" }, 2_500) +
            "\nTell the user briefly in Hindi what happened (summarise long output, never read paths character by character, never invent output). It is also shown in the Terminal tab."
    }
}
