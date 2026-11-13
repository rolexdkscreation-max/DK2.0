package com.dk.assistant.dkai

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 13 नवंबर 2026 (मालिक): Preview का असली localhost सर्वर (http://localhost:8080) - प्रोजेक्ट फ़ोल्डर की फ़ाइलें परोसता है.
 * सिर्फ़ 127.0.0.1 पर (फ़ोन के बाहर से कोई नहीं खोल सकता). Server & Process Monitor इसी की सूची दिखाता/रोकता है.
 */
object DkaiServer {
    class Running(val port: Int, val root: File, val started: Long, internal val sock: ServerSocket) {
        @Volatile var hits = 0
        val url get() = DkaiKit.localUrl(port)
    }

    private val servers = ConcurrentHashMap<Int, Running>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dkai-http").apply { isDaemon = true } }
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { for (l in listeners) try { l() } catch (_: Throwable) {} }

    fun list(): List<Running> = servers.values.filter { !it.sock.isClosed }.sortedBy { it.port }
    fun at(port: Int): Running? = servers[port]?.takeIf { !it.sock.isClosed }
    fun running(port: Int = DkaiKit.PORT) = at(port) != null

    /** [root] को [port] पर चलाओ (उसी port पर दूसरा फ़ोल्डर चल रहा हो तो उसे बदल दो). गलती = संदेश */
    @Synchronized fun start(root: File, port: Int = DkaiKit.PORT): String? {
        at(port)?.let { if (it.root.canonicalPath == root.canonicalPath) return null; stop(port) }
        return try {
            val ss = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
            val r = Running(port, root, System.currentTimeMillis(), ss)
            servers[port] = r
            pool.execute { loop(r) }
            changed(); null
        } catch (e: Exception) { "Port $port नहीं खुला: ${e.message?.take(80)}" }
    }

    @Synchronized fun stop(port: Int) {
        servers.remove(port)?.let { try { it.sock.close() } catch (_: Exception) {} }
        changed()
    }

    fun stopAll() { for (p in servers.keys.toList()) stop(p) }

    private fun loop(r: Running) {
        while (!r.sock.isClosed) {
            val s = try { r.sock.accept() } catch (_: Exception) { break }
            pool.execute { serve(r, s) }
        }
        if (servers[r.port] === r) { servers.remove(r.port); changed() }
    }

    private fun serve(r: Running, s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 10_000
                val inp = BufferedInputStream(sock.getInputStream())
                val line = readLine(inp) ?: return
                while (true) { val h = readLine(inp) ?: break; if (h.isEmpty()) break }
                val parts = line.split(' ')
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                val out = sock.getOutputStream()
                r.hits++
                if (method != "GET" && method != "HEAD") { head(out, 405, "text/plain", 0); return }
                val f = DkaiKit.resolve(r.root, path)
                if (f == null || !f.isFile) {
                    val body = "404 - ${path.take(200)} नहीं मिला".toByteArray()
                    head(out, 404, "text/plain; charset=utf-8", body.size.toLong()); if (method == "GET") out.write(body); out.flush(); return
                }
                head(out, 200, DkaiKit.mimeOf(f.name), f.length())
                if (method == "GET") f.inputStream().use { it.copyTo(out, 16 * 1024) }
                out.flush()
            } catch (_: Exception) {}
        }
    }

    private fun head(o: OutputStream, code: Int, type: String, len: Long) {
        val msg = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        o.write(("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: $len\r\nCache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }
}
