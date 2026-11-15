package com.dk.assistant.plus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.dk.assistant.DKLog

/**
 * 15 नवंबर 2026 (मालिक, Part 3 - Termux Automation, default बंद):
 * Termux के `RunCommandService` से कमांड चलाना (`com.termux.permission.RUN_COMMAND`), script लिखना, पीछे लोकल सर्वर
 * चलाना, `curl` से port जाँचना और `http://127.0.0.1:<port>` Chrome में खोलना.
 * Termux में एक बार: `~/.termux/termux.properties` में `allow-external-apps = true` (Settings में यही मदद दिखती है).
 * Pure हिस्सा ([Rules]) JVM test में ([TermuxTest]).
 */
object Termux {
    const val PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"

    object Rules {
        /** port सिर्फ़ 1024-65535 (system port नहीं) */
        fun validPort(p: Int) = p in 1024..65535

        /** shell के लिए सुरक्षित single-quote */
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** फ़ोल्डर का नाम - सिर्फ़ अक्षर/अंक/_/- (path बाहर न जाए) */
        fun safeName(s: String) = s.trim().matches(Regex("^[A-Za-z0-9_-]{1,40}$"))

        /**
         * 15 नवंबर 2026: DK लाइव बोलकर Termux कमांड चलाए - फ़ोन/डेटा मिटाने या हमेशा अटकाने वाली कमांड कभी नहीं
         * (वह यूज़र खुद Termux में चलाए).
         */
        private val DANGER = listOf(
            Regex("""\brm\s+(-\S*\s+)*-\S*[rRf]\S*\s+(\S+\s+)*((/|~/?|${'$'}HOME/?|\*|\.)(\s|$)|/(sdcard|storage|data)\b)"""),
            Regex("""\bmkfs\b"""), Regex("""\bdd\s+[^|]*of=/dev/"""), Regex(""":\(\)\s*\{"""),
            Regex("""\b(reboot|shutdown|poweroff)\b"""), Regex("""\bchmod\s+-R\s+0*\s*/(\s|$)"""),
            Regex("""(curl|wget)[^|]*\|\s*(ba)?sh\b"""), Regex("""\bsu\b"""),
        )
        fun dangerous(cmd: String): Boolean = DANGER.any { it.containsMatchIn(cmd) }

        enum class Kind { STATIC, PYTHON, NODE }

        /** पीछे चलने वाला सर्वर (nohup) + PID फ़ाइल + 2 सेकंड बाद curl से जाँच */
        fun serverCmd(dir: String, port: Int, kind: Kind, entry: String = ""): String {
            require(validPort(port)) { "port गलत" }
            require(safeName(dir)) { "फ़ोल्डर का नाम गलत" }
            val run = when (kind) {
                Kind.STATIC -> "python -m http.server $port --bind 127.0.0.1"
                Kind.PYTHON -> "PORT=$port python ${q(entry.ifBlank { "app.py" })}"
                Kind.NODE -> "PORT=$port node ${q(entry.ifBlank { "server.js" })}"
            }
            return "mkdir -p ~/dk/$dir && cd ~/dk/$dir && (nohup $run > server.log 2>&1 & echo \$! > server.pid) && sleep 2 && " +
                "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/ || echo DOWN"
        }

        /** फ़ाइल लिखना (heredoc, नाम सुरक्षित) */
        fun writeFileCmd(dir: String, name: String, content: String): String {
            require(safeName(dir)) { "फ़ोल्डर का नाम गलत" }
            require(name.matches(Regex("^[A-Za-z0-9_.-]{1,60}$")) && !name.contains("..")) { "फ़ाइल का नाम गलत" }
            val tag = "DK_EOF_" + (content.hashCode().toUInt().toString(16))
            return "mkdir -p ~/dk/$dir && cat > ~/dk/$dir/$name <<'$tag'\n$content\n$tag"
        }

        fun portCheckCmd(port: Int) = "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/ || echo DOWN"
        fun localUrl(port: Int) = "http://127.0.0.1:$port"
    }

    fun installed(c: Context): Boolean = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }
    fun permitted(c: Context): Boolean = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** null = भेज दिया; वरना वजह (बोलने लायक) */
    fun run(c: Context, command: String, background: Boolean = true, workDir: String = HOME): String? {
        if (!FeatureFlags.on(c, FeatureFlags.TERMUX)) return "Termux ऑटोमेशन बंद है। सेटिंग्स → कोडिंग एजेंट → Termux ऑटोमेशन में चालू करें।"
        if (!installed(c)) return "Termux इंस्टॉल नहीं है (F-Droid / GitHub वाला Termux डालें)।"
        if (!permitted(c)) return "Termux की RUN_COMMAND इजाज़त नहीं है। सेटिंग्स → परमिशन में दें।"
        val i = Intent(ACTION).apply {
            component = ComponentName(PKG, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        return try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            DKLog.d("Termux: कमांड भेजी (${command.take(60)})")
            null
        } catch (e: Exception) { "Termux तक कमांड नहीं पहुँची: ${e.message}" }
    }

    fun startServer(c: Context, dir: String, port: Int, kind: Rules.Kind, entry: String = ""): String? =
        try { run(c, Rules.serverCmd(dir, port, kind, entry)) } catch (e: IllegalArgumentException) { e.message }

    fun openLocal(c: Context, port: Int): String? = try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Rules.localUrl(port))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (isChrome(c)) setPackage("com.android.chrome") })
        null
    } catch (e: Exception) { e.message }

    private fun isChrome(c: Context) = try { c.packageManager.getPackageInfo("com.android.chrome", 0); true } catch (_: Exception) { false }
}
