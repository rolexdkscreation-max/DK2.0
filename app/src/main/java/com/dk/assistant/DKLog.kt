package com.dk.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Chhota sa logger: Logcat me bhi likhta hai aur app screen par bhi dikhata hai. */
object DKLog {
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    var listener: ((String) -> Unit)? = null

    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private var postQueued = false
    /** नवंबर 2026 (स्मूथ): पहले हर लाइन पर 300 लाइनें जोड़कर स्क्रीन को भेजी जाती थीं (कोई देख न रहा हो तब भी).
     *  अब सिर्फ़ जब लॉग स्क्रीन खुली हो, और कई लाइनें मिलकर एक बार (~10/sec) */
    private val emit = Runnable {
        val all = synchronized(this) { postQueued = false; if (listener == null) null else lines.joinToString("\n") }
        if (all != null) listener?.invoke(all)
    }

    @Synchronized
    fun d(msg: String) {
        Log.d("DK", msg)
        val line = fmt.format(java.util.Date()) + " " + msg
        lines.addLast(line)
        // नवंबर 2026: DK Inspector के लिए फ़ाइल में भी (ऐप बंद होने पर भी बचे) - key/बातचीत छिपाकर, अलग thread पर
        DKBug.logFile?.append(System.currentTimeMillis(), line)
        while (lines.size > 300) lines.removeFirst()
        if (listener != null && !postQueued) { postQueued = true; main.postDelayed(emit, 100) }
    }

    @Synchronized
    fun clear() { lines.clear(); main.post { listener?.invoke("") } }

    @Synchronized
    fun all(): String = lines.joinToString("\n")
}
