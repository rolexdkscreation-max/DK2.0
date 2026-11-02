package com.dk.assistant

import android.content.Context
import android.util.Log
import com.dk.assistant.inspector.InspectorCore
import java.io.File
import java.util.concurrent.Executors

/**
 * नवंबर 2026 (मालिक): "DK Inspector को पूरे ऐप से जोड़ो - कहीं भी बग आए तो उसे पता हो".
 *
 *  - [report]: ऐप का कोई भी हिस्सा अपनी पकड़ी हुई गलती यहाँ दर्ज करे (call, connector, update, assistant…).
 *    गलती फ़ाइल में जाती है - DK Inspector हर 20 मिनट पढ़ता है. इससे कोई काम नहीं रुकता, कभी exception नहीं फेंकता.
 *  - [logFile]: DKLog की लाइनें भी फ़ाइल में - ऐप/फ़ोन बंद होने पर भी Inspector पढ़ सके.
 *  - सब फ़ाइलें ऐप के अपने निजी हिस्से में, API key / token / बातचीत पहले ही छिपाकर ([InspectorCore.redact]).
 */
object DKBug {
    @Volatile var logFile: Feed? = null; private set
    @Volatile var bugFile: Feed? = null; private set

    fun init(c: Context) {
        try {
            val dir = File(c.applicationContext.filesDir, "inspector").apply { mkdirs() }
            logFile = Feed(File(dir, "dk_log.txt"))
            bugFile = Feed(File(dir, "dk_bugs.txt"))
        } catch (_: Throwable) {}
    }

    /**
     * [area] = हिस्सा (call / connector / update / assistant / notification / github / settings / crash / app),
     * [what] = क्या करते समय, [e] = पकड़ी गई गलती. [alsoLog] = Debug के लॉग में भी दिखे (जहाँ पहले सिर्फ़ Logcat था)
     */
    fun report(area: String, e: Throwable?, what: String, alsoLog: Boolean = false) {
        try {
            // नेट न होना / रुकवाया गया काम = code की गलती नहीं (Inspector की सूची न भरे)
            if (e != null && notBug(e)) return
            val msg = InspectorCore.bugLine(area, what, e?.javaClass?.simpleName, e?.message, e?.let { frame(it) })
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            // alsoLog = DKLog खुद अपनी फ़ाइल में लिखता है (दो बार न गिने)
            if (alsoLog) DKLog.d(msg) else { bugFile?.append(System.currentTimeMillis(), "$time $msg"); Log.w("DK", msg) }
        } catch (_: Throwable) {}
    }

    private fun notBug(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(4).any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
            it is java.net.NoRouteToHostException || it is javax.net.ssl.SSLException || it is kotlinx.coroutines.CancellationException ||
            it is InterruptedException
    }

    /** DK के अपने code की पहली लाइन (file:line) - Coding Agent को जगह मिले */
    private fun frame(e: Throwable): String? = try {
        e.stackTrace.firstOrNull { it.className.startsWith("com.dk.") }?.let { "${it.fileName}:${it.lineNumber}" }
    } catch (_: Throwable) { null }

    /** एक छोटी घूमती फ़ाइल: हर लाइन = "<समय ms>\t<HH:mm:ss.SSS संदेश>"; 256 KB के बाद पुरानी .1 बनती है (बस 2 फ़ाइल) */
    class Feed(private val f: File, private val maxBytes: Long = 256 * 1024L) {
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-inspector-feed").apply { isDaemon = true } }
        private val old get() = File(f.path + ".1")

        fun append(at: Long, line: String) {
            try {
                io.execute {
                    try {
                        if (f.length() > maxBytes) { old.delete(); f.renameTo(old) }
                        f.appendText(at.toString() + "\t" + InspectorCore.redact(line).replace('\n', ' ').take(1200) + "\n")
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        /** पूरी फ़ाइल (पुरानी + नई) - Inspector [InspectorCore.feedSince] से सिर्फ़ नई लाइनें लेता है */
        fun read(): String = try {
            (if (old.exists()) old.readText() else "") + (if (f.exists()) f.readText() else "")
        } catch (_: Throwable) { "" }

        fun exists(): Boolean = try { f.exists() || old.exists() } catch (_: Throwable) { false }
    }
}
