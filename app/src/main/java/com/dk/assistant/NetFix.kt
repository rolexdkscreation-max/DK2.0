package com.dk.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * मालिक (01 नवंबर 2026): "एक Wi-Fi/नेटवर्क पर DK नहीं चलता, दूसरे पर तुरंत चलता है; पहले उसी नेटवर्क पर ठीक था"।
 * उसी नेटवर्क पर Chrome Google तक पहुँच गया (403 वाला जवाब), पर DK का हर Google/Microsoft connection अटका, Groq/OpenRouter चले.
 *
 * जड़: Chrome के पास अपना DNS (Secure DNS), IPv4/IPv6 दोनों की एक साथ दौड़ (Happy Eyeballs) और QUIC है.
 * DK (OkHttp 4.12) फ़ोन के नेटवर्क का DNS लेता है और पते एक-एक करके आज़माता है - पहला पता अक्सर IPv6.
 * किसी router/ISP का IPv6 या DNS टूटा/बदला हो = पहला पता अटकता है, Live की हर जोड़ी 5.6s में हार जाती है, अगले
 * (IPv4) पते तक बारी आती ही नहीं. दूसरा नेटवर्क = दूसरा DNS/रास्ता = चल जाता है.
 *
 * इलाज (पूरे ऐप के हर OkHttp client पर, [builder] से):
 *  1. IPv4 पते पहले, फिर IPv6 (टूटे IPv6 वाले नेटवर्क पर भी तुरंत चलता है; IPv6-only नेटवर्क पर IPv6 फिर भी मौजूद)
 *  2. फ़ोन का DNS 2.5s में जवाब न दे / गलती दे = DNS-over-HTTPS (Cloudflare 1.1.1.1, फिर Google 8.8.8.8) से पता
 *  3. जो पता connect नहीं हुआ उसे 10 मिनट पीछे; Google/Microsoft तक न पहुँचे तो उस host के लिए DoH वाले पते भी जोड़ो
 *  4. नेटवर्क बदलते ही सारी याद साफ़ (नया नेटवर्क = नई शुरुआत)
 */
object DkDns : Dns {
    private fun log(m: String) { try { DKLog.d(m) } catch (_: Throwable) {} }   // unit test में Android नहीं
    private const val SYS_WAIT_MS = 2_500L
    private const val CACHE_MS = 5 * 60_000L
    private const val BAD_MS = 10 * 60_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val badIp = ConcurrentHashMap<String, Long>()
    private val dohFirst = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dk-dns").apply { isDaemon = true } }

    /** DoH के लिए अलग client - IP वाले URL (1.1.1.1 / 8.8.8.8), इसलिए इसे खुद DNS नहीं चाहिए */
    private val dohHttp by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (at, list) -> if (now - at < CACHE_MS && list.isNotEmpty()) return order(list, now) }
        val sys: List<InetAddress>? = try {
            pool.submit<List<InetAddress>> { Dns.SYSTEM.lookup(hostname) }.get(SYS_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
        val wantDoh = sys.isNullOrEmpty() || (dohFirst[hostname] ?: 0L) > now
        val doh = if (wantDoh) doh(hostname) else emptyList()
        if (sys.isNullOrEmpty() && doh.isNotEmpty()) log("नेटवर्क: फ़ोन के DNS ने $hostname का पता नहीं दिया - DoH से मिला")
        val all = (doh + (sys ?: emptyList())).distinctBy { it.hostAddress }
        if (all.isEmpty()) throw UnknownHostException("$hostname का पता नहीं मिला (फ़ोन DNS + DoH दोनों)")
        cache[hostname] = now to all
        return order(all, now)
    }

    /** IPv4 पहले, फिर IPv6; अभी-अभी अटके पते सबसे पीछे */
    internal fun order(list: List<InetAddress>, now: Long = System.currentTimeMillis()): List<InetAddress> =
        list.sortedWith(compareBy<InetAddress>({ (badIp[it.hostAddress ?: ""] ?: 0L) > now }, { it !is Inet4Address }))

    /** इस पते पर connection नहीं बना - 10 मिनट पीछे */
    fun markBad(addr: InetAddress?) {
        val a = addr?.hostAddress ?: return
        badIp[a] = System.currentTimeMillis() + BAD_MS
    }

    /** WebSocket (Live / Edge) host तक connection खुला ही नहीं - अगली बार DoH वाले पते भी, पुराने पते पीछे */
    /**
     * नवंबर 2026: फ़ोन का नेटवर्क सच में बदला (Wi-Fi <-> mobile) कब - Live तुरंत नया connection बनाता है.
     * सिर्फ़ नया नेटवर्क; IPv6 पते का बदलना (onLinkPropertiesChanged) नहीं - वह बार-बार होता है.
     */
    @Volatile var switchedAt = 0L

    fun reportUnreachable(host: String) {
        val now = System.currentTimeMillis()
        cache.remove(host)?.second?.forEach { markBad(it) }
        dohFirst[host] = now + BAD_MS
        log("नेटवर्क: $host तक connection नहीं खुला - अगली बार दूसरे पते (IPv4 पहले + DoH)")
    }

    fun onNetworkChanged(why: String) {
        if (cache.isEmpty() && badIp.isEmpty() && dohFirst.isEmpty()) return
        cache.clear(); badIp.clear(); dohFirst.clear()
        log("नेटवर्क बदला ($why) - DNS/पतों की याद साफ़")
    }

    private fun doh(host: String): List<InetAddress> {
        val urls = listOf(
            "https://1.1.1.1/dns-query?name=$host&type=A",
            "https://8.8.8.8/resolve?name=$host&type=A"
        )
        for (u in urls) try {
            val req = okhttp3.Request.Builder().url(u).header("accept", "application/dns-json").build()
            dohHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("Answer") ?: return@use
                val out = ArrayList<InetAddress>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optInt("type") == 1) o.optString("data").takeIf { isIpLiteral(it) }?.let { ip ->
                        out += InetAddress.getByAddress(host, InetAddress.getByName(ip).address)
                    }
                }
                if (out.isNotEmpty()) return out
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    internal fun isIpLiteral(s: String): Boolean =
        s.contains(':') || (s.isNotEmpty() && s.all { it.isDigit() || it == '.' } && s.count { it == '.' } == 3)
}

object NetFix {
    /** हर नाकाम connect = वह पता पीछे (अगली कोशिश दूसरे पते से) */
    private val listener = object : EventListener() {
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            DkDns.markBad(inetSocketAddress.address)
        }
    }

    /** पूरे ऐप में OkHttpClient.Builder() की जगह यही - DkDns (IPv4 पहले + DoH) + अटके पतों की याद */
    @JvmStatic fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().dns(DkDns).eventListener(listener)

    @Volatile private var installed = false

    /** ऐप शुरू होते ही: नेटवर्क बदले (Wi-Fi ↔ data, दूसरा Wi-Fi, DNS बदला) तो याद साफ़ */
    fun install(c: Context) {
        if (installed) return
        installed = true
        try {
            val cm = c.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile private var last: Network? = null
                override fun onAvailable(network: Network) {
                    if (last != null && last != network) { DkDns.switchedAt = System.currentTimeMillis(); DkDns.onNetworkChanged("नया नेटवर्क") }
                    last = network
                }
                @Volatile private var sig = ""
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    // सिर्फ़ DNS server या फ़ोन का पता सच में बदले तब (यह callback बार-बार आता है)
                    val now = lp.dnsServers.joinToString { it.hostAddress ?: "" } + "|" + lp.linkAddresses.joinToString { it.toString() }
                    if (sig.isNotEmpty() && sig != now) DkDns.onNetworkChanged("DNS/पता बदला")
                    sig = now
                }
            })
        } catch (e: Exception) { DKLog.d("नेटवर्क निगरानी शुरू नहीं हुई: ${e.message}") }
    }
}
