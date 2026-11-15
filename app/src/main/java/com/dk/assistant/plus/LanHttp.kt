package com.dk.assistant.plus

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * 15 नवंबर 2026: घर के relay (ESP8266 / Tasmota / Sonoff) अक्सर सिर्फ़ `http://192.168.x.x` पर चलते हैं.
 * पूरे ऐप में cleartext HTTP खोलना असुरक्षित है ([dkai_network.xml] सिर्फ़ localhost देता है) - इसलिए यह छोटा
 * HTTP/1.0 sender **सिर्फ़ घर के अपने नेटवर्क** (private IP / .local) के लिए. Internet के पते पर हमेशा मना.
 * Pure जाँच ([isLan]) JVM test में.
 */
object LanHttp {
    fun isLan(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    /** @return HTTP status code; गलत/बाहर का पता = IllegalArgumentException */
    fun send(url: String, post: Boolean, body: String = "", timeoutMs: Int = 6000): Int {
        val u = URI(url)
        require(u.scheme == "http") { "सिर्फ़ http://" }
        val host = u.host ?: throw IllegalArgumentException("पता गलत")
        require(isLan(host)) { "सिर्फ़ घर के नेटवर्क (192.168.x.x / 10.x / .local) के पते" }
        val port = if (u.port > 0) u.port else 80
        val path = (u.rawPath?.ifBlank { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
            val bytes = body.toByteArray()
            val req = buildString {
                append(if (post) "POST" else "GET").append(' ').append(path).append(" HTTP/1.0\r\n")
                append("Host: ").append(host).append("\r\n").append("User-Agent: DK-AI-Home\r\nConnection: close\r\n")
                if (post) append("Content-Type: application/json\r\nContent-Length: ").append(bytes.size).append("\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(req.toByteArray()); if (post) write(bytes); flush() }
            val line = s.getInputStream().bufferedReader().readLine() ?: throw java.io.IOException("जवाब नहीं")
            return line.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("जवाब समझ नहीं आया")
        }
    }
}
