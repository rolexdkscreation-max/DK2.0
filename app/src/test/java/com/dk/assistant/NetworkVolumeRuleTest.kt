package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * मालिक (01 नवंबर 2026):
 *  1. "एक नेटवर्क पर DK नहीं चलता, दूसरे पर चलता है" - उसी नेटवर्क पर Chrome Google तक पहुँचा (403 JSON)
 *     -> हर OkHttp client पर DkDns (IPv4 पहले + DoH + अटके पते पीछे)
 *  2. "कोई भी आवाज़ अपने-आप कम/ज़्यादा नहीं" -> VolumeRule.AUTO_CHANGE = false
 */
class NetworkVolumeRuleTest {
    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }
        return f.readText()
    }
    private fun allMain(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test fun ipv4First() {
        val list = listOf(ip("2001:4860:4847:400::"), ip("172.217.112.4"), ip("2001:4860:4840:400::"), ip("172.217.113.4"))
        val o = DkDns.order(list, 0L)
        assertEquals("172.217.112.4", o[0].hostAddress)
        assertEquals("172.217.113.4", o[1].hostAddress)
        assertEquals(4, o.size)   // IPv6 हटाया नहीं - सिर्फ़ पीछे (IPv6-only नेटवर्क पर भी चले)
    }

    @Test fun badAddressGoesLast() {
        val a = ip("10.9.8.7"); val b = ip("10.9.8.6")
        DkDns.markBad(a)
        val o = DkDns.order(listOf(a, b), System.currentTimeMillis())
        assertEquals("10.9.8.6", o[0].hostAddress)
        DkDns.onNetworkChanged("test")
        assertEquals("10.9.8.7", DkDns.order(listOf(a, b), System.currentTimeMillis())[0].hostAddress)
    }

    @Test fun ipLiteral() {
        assertTrue(DkDns.isIpLiteral("1.1.1.1"))
        assertTrue(DkDns.isIpLiteral("2001:4860::1"))
        assertFalse(DkDns.isIpLiteral("generativelanguage.googleapis.com"))
        assertFalse(DkDns.isIpLiteral("1.1.1"))
    }

    /** पूरे ऐप में कहीं भी सीधा OkHttpClient.Builder() नहीं (NetFix के अंदर के अलावा) - वरना वह client पुराने DNS पर अटकेगा */
    @Test fun everyClientUsesNetFix() {
        val bad = allMain().filter { it.name != "NetFix.kt" && Regex("""OkHttpClient\.Builder\(\)""").containsMatchIn(it.readText()) }
        assertTrue("सीधा OkHttpClient.Builder(): ${bad.map { it.name }}", bad.isEmpty())
        val n = src("NetFix.kt")
        assertTrue(n.contains(".dns(DkDns)") && n.contains("https://1.1.1.1/dns-query") && n.contains("https://8.8.8.8/resolve"))
        assertTrue(n.contains("registerDefaultNetworkCallback"))
        assertTrue(src("DKApp.kt").contains("NetFix.install(this)"))
        // Live की जोड़ी 5.6s में हारती है - connect 4s ताकि अगला पता भी आज़माया जाए
        assertTrue(src("LiveNet.kt").contains(".connectTimeout(4, TimeUnit.SECONDS)"))
        assertTrue(LiveSrc.all().contains("DkDns.reportUnreachable(LIVE_HOST)"))
        assertTrue(src("voice/engine/EdgeTtsEngine.kt").contains("DkDns.reportUnreachable(\"speech.platform.bing.com\")"))
    }

    @Test fun noAutoVolume() {
        assertFalse(VolumeRule.AUTO_CHANGE)
        val d = src("Ducker.kt")
        assertTrue(d.contains("if (!VolumeRule.AUTO_CHANGE) return@post"))
        assertFalse(d.contains("currentMusicVol / 2"))
        val sp = src("Speaker.kt")
        assertTrue(sp.contains("if (!VolumeRule.AUTO_CHANGE) return       // मालिक (नवंबर 2026): कॉल की आवाज़"))
        assertTrue(sp.contains("DK खुद नहीं बढ़ाएगा"))
        assertTrue(src("VoiceListener.kt").contains("if (on && !VolumeRule.AUTO_CHANGE) return"))
        assertTrue(LiveSrc.all().contains("if (VolumeRule.AUTO_CHANGE) track?.let { try { it.setVolume(SpeakerBoost.SUSPECT_VOLUME) }"))
        // यूज़र के कहने पर आवाज़ बदलना अब भी चलता है
        assertTrue(src("DeviceActions.kt").contains("ADJUST_LOWER"))
    }
}
