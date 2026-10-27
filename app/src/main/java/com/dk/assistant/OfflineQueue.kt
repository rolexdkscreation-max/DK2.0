package com.dk.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.88: नेटवर्क टूटे तो काम खोए नहीं. AI तक नहीं पहुँच पाए और फ़ोन पर इंटरनेट है ही नहीं = काम यहाँ
 * (फ़ोन में सेव, ऐप बंद हो जाए तब भी) - नेटवर्क आते ही उसी क्रम में अपने-आप आगे.
 * 6 घंटे से पुराना काम दोबारा नहीं चलता (तब तक बात बदल चुकी होगी). ज़्यादा से ज़्यादा 5.
 */
object OfflineQueue {
    private const val KEY = "offline_goals"
    private const val MAX = 5
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    @Volatile private var installed = false
    @Volatile private var draining = false

    private fun sp(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    private fun load(c: Context): MutableList<Pair<String, Long>> = try {
        val a = JSONArray(sp(c).getString(KEY, "[]"))
        MutableList(a.length()) { a.getJSONObject(it).let { o -> o.optString("g") to o.optLong("at") } }
    } catch (_: Exception) { mutableListOf() }

    private fun save(c: Context, l: List<Pair<String, Long>>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("g", it.first).put("at", it.second)) }
        sp(c).edit().putString(KEY, a.toString()).apply()
    }

    fun size(c: Context): Int = load(c).size

    /** true = कतार में रखा (तब यूज़र को "नेटवर्क आते ही" बताओ) */
    @Synchronized fun add(c: Context, goal: String): Boolean {
        val g = goal.trim()
        if (g.length < 2 || Sensitive.isSensitive(g)) return false
        val l = load(c).filter { it.first != g && System.currentTimeMillis() - it.second < MAX_AGE_MS }.toMutableList()
        l.add(g to System.currentTimeMillis())
        save(c, l.takeLast(MAX))
        install(c)
        DKLog.d("नेटवर्क नहीं - काम कतार में (${l.size.coerceAtMost(MAX)}): \"${g.take(40)}\"")
        return true
    }

    /** ऐप शुरू होते ही एक बार: नेटवर्क आने पर कतार आगे */
    fun install(c: Context) {
        if (installed) return
        try {
            val cm = c.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                drain(c.applicationContext)
                // DK कंप्यूटर का नेटवर्क की वजह से रुका काम
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { if (NetProbe.online(c)) ComputerSession.resumeIfNetPaused(c.applicationContext) } catch (_: Exception) {}
                }, 3_000)
            }
            })
            installed = true
        } catch (e: Exception) { DKLog.d("OfflineQueue: नेटवर्क सुनना शुरू नहीं हुआ (${e.javaClass.simpleName})") }
    }

    fun drain(c: Context) {
        if (draining || load(c).isEmpty()) return   // (DK कंप्यूटर का रुका काम: ComputerSession.resumeIfNetPaused)
        val svc = DKAccessibilityService.instance ?: return       // सेवा चालू होते ही DKApp/सेवा फिर बुलाएगी
        draining = true
        svc.scope.launch {
            try {
                delay(2_500)                                         // नेटवर्क पक्का जुड़ जाए
                if (!NetProbe.online(svc)) return@launch
                val todo = synchronized(this@OfflineQueue) { load(svc).also { save(svc, emptyList()) } }
                    .filter { System.currentTimeMillis() - it.second < MAX_AGE_MS }
                for ((g, _) in todo) {
                    while (svc.agent.isBusy) delay(400)
                    DKLog.d("नेटवर्क आ गया - कतार का काम आगे: \"${g.take(40)}\"")
                    svc.agent.run(g).join()
                }
            } catch (e: Exception) { DKLog.d("OfflineQueue: ${e.javaClass.simpleName}") }
            finally { draining = false }
        }
    }
}
