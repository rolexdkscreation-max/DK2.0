package com.dk.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Chhota sa logger: Logcat me bhi likhta hai aur app screen par bhi dikhata hai. */
object DKLog {
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun d(msg: String) {
        Log.d("DK", msg)
        lines.addLast(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + " " + msg)
        while (lines.size > 300) lines.removeFirst()
        val all = lines.joinToString("\n")
        main.post { listener?.invoke(all) }
    }

    @Synchronized
    fun clear() { lines.clear(); main.post { listener?.invoke("") } }

    @Synchronized
    fun all(): String = lines.joinToString("\n")
}
