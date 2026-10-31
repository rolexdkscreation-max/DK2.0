package com.dk.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v5.1: DK लाइव का एक ही साझा OkHttpClient (connection pool के साथ) + स्क्रीन चालू होने पर पहले से गरम करना.
 *
 * गरम करना = Gemini Live के host का DNS + TCP + TLS पहले से (एक छोटा HTTPS अनुरोध, बिना key).
 * यह Live सत्र नहीं खोलता, माइक नहीं छूता, कोई आवाज़ नहीं भेजता. हर बार स्क्रीन चालू होने पर सिर्फ़ एक बार;
 * स्क्रीन बंद = चलता अनुरोध रद्द और खाली connection बंद (battery).
 * कोई हमेशा-चालू service नहीं - receiver DK की accessibility service (जो पहले से चलती है) के अंदर dynamic है.
 */
object LiveNet {
    const val HOST = "generativelanguage.googleapis.com"

    val pool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    /** GeminiLive का WebSocket इसी से (पहले हर GeminiLive का अपना client था) */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(pool)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)          // कनेक्शन को लगातार zinda रखने के लिए
            .retryOnConnectionFailure(true)
            .build()
    }

    /** WebSocket HTTP/1.1 पर चलता है - गरम connection भी HTTP/1.1 का, ताकि वही connection दोबारा काम आए */
    private val warmHttp: OkHttpClient by lazy {
        http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).callTimeout(8, TimeUnit.SECONDS).build()
    }

    @Volatile private var warmedThisScreen = false
    @Volatile private var warmCall: Call? = null
    @Volatile var lastWarmMs = -1L
        private set
    private var receiver: BroadcastReceiver? = null

    /** स्क्रीन चालू/बंद सुनो (dynamic - service बंद तो यह भी बंद) */
    fun register(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> warm(ctx, "स्क्रीन चालू")
                    Intent.ACTION_SCREEN_OFF -> screenOff()
                }
            }
        }
        try {
            val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
            c.registerReceiver(r, f)
            receiver = r
            val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive == true) warm(c, "service शुरू")
        } catch (e: Exception) { DKLog.d("Live: स्क्रीन वाला receiver नहीं लगा: ${e.message}") }
    }

    fun unregister(c: Context) {
        receiver?.let { try { c.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        screenOff()
    }

    /** एक स्क्रीन-चालू में सिर्फ़ एक बार. Live की key न हो / नेट न हो = कुछ नहीं */
    fun warm(c: Context, why: String) {
        if (warmedThisScreen) return
        if (!GeminiLive.available(c) || !NetProbe.online(c)) return
        warmedThisScreen = true
        val t0 = System.currentTimeMillis()
        val call = warmHttp.newCall(Request.Builder().url("https://$HOST/").head().build())
        warmCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                warmCall = null
                if (!call.isCanceled()) DKLog.d("Live: पहले से गरम नहीं हुआ ($why): ${e.javaClass.simpleName}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { }
                warmCall = null
                lastWarmMs = System.currentTimeMillis() - t0
                DKLog.d("Live: host पहले से गरम ($why) - DNS+TLS $lastWarmMs ms (Live सत्र नहीं, माइक नहीं)")
            }
        })
    }

    private fun screenOff() {
        warmedThisScreen = false
        warmCall?.cancel(); warmCall = null
        try { warmHttp.connectionPool.evictAll() } catch (_: Exception) {}
    }
}
