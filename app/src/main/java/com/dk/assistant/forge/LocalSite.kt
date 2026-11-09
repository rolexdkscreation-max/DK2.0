package com.dk.assistant.forge

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dk.assistant.DKLog
import java.io.BufferedOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * नवंबर 2026 (मालिक: "वेबसाइट को लोकली डिप्लॉय करके दे, लिंक दे"): फ़ोन पर ही छोटा वेब सर्वर.
 * सिर्फ़ DK की बनाई चीज़ें दिखाता है - Websites/ (Forge) और DK कंप्यूटर की .html (गेम). कोई फ़ाइल लिखता / बदलता नहीं.
 * लिंक: इसी फ़ोन के Chrome के लिए http://127.0.0.1:PORT/…, और उसी Wi-Fi वाले दूसरे फ़ोन / लैपटॉप के लिए http://<Wi-Fi IP>:PORT/….
 * ऐप बंद हो = सर्वर बंद (इंटरनेट पर सबको दिखाने के लिए [ForgeDeploy], सिर्फ़ कहने पर).
 */
object LocalSite {
    const val PORT = 8765
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0; private set
    @Volatile private var roots: List<File> = emptyList()
    /** नवंबर 2026: ऐप स्टूडियो के project (कई फ़ाइलें, फ़ोल्डर) - /studio/<project>/<रास्ता> */
    @Volatile private var studioRoot: File? = null

    val running get() = server?.isClosed == false

    /** सर्वर चालू करो (पहले से चालू = वही). return = port, या 0 (नहीं चला) */
    @Synchronized fun ensure(c: Context): Int {
        roots = listOf(ForgeController.websitesDir(c), com.dk.assistant.ComputerTools.dir(c))
        studioRoot = com.dk.assistant.studio.StudioProject.root(c)
        if (running) return port
        for (p in PORT until PORT + 10) {
            try {
                val s = ServerSocket(p, 16, InetAddress.getByName("0.0.0.0"))
                server = s; port = p
                thread(name = "dk-local-site", isDaemon = true) { loop(s) }
                DKLog.d("लोकल वेबसाइट सर्वर चालू: port $p")
                return p
            } catch (_: Exception) {}
        }
        DKLog.d("लोकल वेबसाइट सर्वर नहीं चला (port $PORT-${PORT + 9} व्यस्त)")
        return 0
    }

    @Synchronized fun stop() { try { server?.close() } catch (_: Exception) {}; server = null; port = 0 }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) { try { serve(sock) } catch (_: Exception) {} finally { try { sock.close() } catch (_: Exception) {} } }
        }
    }

    private fun serve(sock: Socket) {
        sock.soTimeout = 10_000
        val line = sock.getInputStream().bufferedReader().readLine() ?: return
        val path = line.split(' ').getOrNull(1) ?: "/"
        val out = BufferedOutputStream(sock.getOutputStream())
        val f = if (path.startsWith(STUDIO)) studioRoot?.let { resolveStudio(path, it) } else resolve(path, roots)
        if (f == null) {
            val body = indexHtml(roots).toByteArray()
            val code = if (path == "/" || path.isBlank()) "200 OK" else "404 Not Found"
            out.write(("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(body); out.flush(); return
        }
        val bytes = f.readBytes()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${contentType(f.name)}\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes); out.flush()
    }

    // ---------------------------------------------------------------- साफ़ (JVM test वाले) हिस्से
    /** "/website_1.html?x" -> सुरक्षित फ़ाइल नाम; फ़ोल्डर / ".." / छिपी फ़ाइल नहीं. "/latest" = सबसे नई */
    fun safeName(path: String): String? {
        val p = try { URLDecoder.decode(path.substringBefore('?').substringBefore('#'), "UTF-8") } catch (_: Exception) { return null }
        val n = p.trim('/')
        if (n.isEmpty() || n.contains('/') || n.contains('\\') || n.contains("..") || n.startsWith(".")) return null
        return n
    }

    fun resolve(path: String, dirs: List<File>): File? {
        val n = safeName(path) ?: return null
        if (n == "latest" || n == "latest.html") return dirs.flatMap { d -> d.listFiles()?.filter { it.isFile && isPage(it.name) }.orEmpty() }.maxByOrNull { it.lastModified() }
        return dirs.map { File(it, n) }.firstOrNull { it.isFile && it.parentFile?.canonicalPath == it.canonicalFile.parentFile?.canonicalPath }
    }

    const val STUDIO = "/studio/"

    /**
     * "/studio/todo-app-123/css/style.css" -> उसी project के अंदर की फ़ाइल. फ़ोल्डर माँगा = उसका index.html.
     * ".." / छिपी फ़ाइल / project के बाहर = null
     */
    fun resolveStudio(path: String, root: File): File? {
        val p = try { URLDecoder.decode(path.substringBefore('?').substringBefore('#'), "UTF-8") } catch (_: Exception) { return null }
        val rest = p.removePrefix(STUDIO).trim('/')
        if (rest.isEmpty() || rest.contains('\\')) return null
        val parts = rest.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (!parts[0].matches(Regex("[a-z0-9-]{1,48}"))) return null
        var f = File(root, parts.joinToString("/"))
        if (f.isDirectory) f = File(f, "index.html")
        val base = File(root, parts[0]).canonicalPath + File.separator
        return f.takeIf { it.isFile && it.canonicalPath.startsWith(base) }
    }

    fun studioUrl(slug: String, entry: String, p: Int = port) = "http://127.0.0.1:$p$STUDIO$slug/" + entry.split('/').joinToString("/") { enc(it) }
    fun lanStudioUrl(slug: String, entry: String, p: Int = port): String? = lanIp()?.let { "http://$it:$p$STUDIO$slug/" + entry.split('/').joinToString("/") { e -> enc(e) } }

    /** ऐप स्टूडियो का project Chrome में (PWA install भी वहीं से). return = लिंक या null */
    fun openStudio(c: Context, slug: String, entry: String): String? {
        val p = ensure(c)
        if (p == 0) return null
        val url = studioUrl(slug, entry, p)
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try { c.startActivity(Intent(base).setPackage("com.android.chrome")); true } catch (_: Exception) { false }
            || try { c.startActivity(base); true } catch (_: Exception) { false }
        return if (ok) url else null
    }

    fun isPage(name: String) = name.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }

    fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "txt", "md" -> "text/plain; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        "ico" -> "image/x-icon"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun indexHtml(dirs: List<File>): String {
        val pages = dirs.flatMap { d -> d.listFiles()?.filter { it.isFile && isPage(it.name) }.orEmpty() }.sortedByDescending { it.lastModified() }
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>DK Sites</title>" +
            "<style>body{font-family:sans-serif;background:#0b1020;color:#e8ecff;padding:24px}a{color:#7cc4ff;display:block;padding:10px 0;font-size:18px}</style></head>" +
            "<body><h2>DK की बनाई वेबसाइट / गेम</h2>" + pages.joinToString("") { "<a href='/${it.name}'>${it.name}</a>" } + "</body></html>"
    }

    /** Wi-Fi / हॉटस्पॉट वाला पता (192.168.x / 10.x / 172.16-31.x) - न मिले तो null */
    fun pickLanIp(ips: List<String>): String? = ips.firstOrNull { it.startsWith("192.168.") }
        ?: ips.firstOrNull { it.startsWith("10.") }
        ?: ips.firstOrNull { ip -> ip.startsWith("172.") && ip.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true }

    fun lanIp(): String? = try {
        pickLanIp(NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress })
    } catch (_: Exception) { null }

    fun enc(name: String): String = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    fun localUrl(name: String, p: Int = port) = "http://127.0.0.1:$p/" + enc(name)
    fun lanUrl(name: String, p: Int = port): String? = lanIp()?.let { "http://$it:$p/" + enc(name) }

    /** सर्वर चालू करके फ़ाइल Chrome में खोलो (Chrome न हो तो कोई भी ब्राउज़र). return = लिंक, या null */
    fun openInChrome(c: Context, f: File): String? {
        val p = ensure(c)
        if (p == 0) return null
        val url = localUrl(f.name, p)
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try { c.startActivity(Intent(base).setPackage("com.android.chrome")); true } catch (_: Exception) { false }
            || try { c.startActivity(base); true } catch (_: Exception) { false }
        DKLog.d("लोकल साइट ब्राउज़र में: ${f.name} (${if (ok) "खुली" else "नहीं खुली"})")
        return if (ok) url else null
    }
}
