package com.dk.assistant.plus

import android.content.Context
import com.dk.assistant.Prefs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 15 नवंबर 2026 (मालिक की सूची): ईमेल पढ़ना / भेजना / हटाना - IMAP + SMTP, सिर्फ़ App Password से (कोई पैसे वाली API नहीं, कोई नई library नहीं).
 * Gmail: myaccount.google.com/apppasswords से 16 अक्षर का App Password. Password [com.dk.assistant.SecretNames] के ताले में.
 *
 * [Rules] = pure logic (JVM test). [Net] = असली सॉकेट (TLS 993 / 465, या 587 STARTTLS).
 */
object Mail {
    const val ADDR = "plus_email_addr"
    const val PASS = "plus_email_pass"          // SecretPrefs में सील
    const val SIGN = "plus_email_sign"
    const val IMAP_HOST = "plus_email_imap"     // खाली = पते से अपने-आप
    const val SMTP_HOST = "plus_email_smtp"

    data class Server(val host: String, val port: Int, val tls: Boolean)   // tls=false = STARTTLS
    data class Msg(val uid: Long, val from: String, val subject: String, val date: String, val text: String, val unread: Boolean)

    object Rules {
        /** पते से सर्वर: Gmail / Yahoo / Outlook / Zoho, बाकी imap.<domain> + smtp.<domain> */
        fun servers(addr: String, imapOverride: String = "", smtpOverride: String = ""): Pair<Server, Server>? {
            val d = addr.substringAfter('@', "").trim().lowercase()
            if (d.isBlank() || !d.contains('.')) return null
            val (i, s) = when (d) {
                "gmail.com", "googlemail.com" -> Server("imap.gmail.com", 993, true) to Server("smtp.gmail.com", 465, true)
                "yahoo.com", "yahoo.in", "yahoo.co.in", "ymail.com" -> Server("imap.mail.yahoo.com", 993, true) to Server("smtp.mail.yahoo.com", 465, true)
                "outlook.com", "hotmail.com", "live.com", "msn.com" -> Server("outlook.office365.com", 993, true) to Server("smtp-mail.outlook.com", 587, false)
                "zoho.com", "zohomail.in", "zoho.in" -> Server("imap.zoho.in", 993, true) to Server("smtp.zoho.in", 465, true)
                else -> Server("imap.$d", 993, true) to Server("smtp.$d", 465, true)
            }
            fun ov(raw: String, def: Server): Server {
                val t = raw.trim(); if (t.isBlank()) return def
                val h = t.substringBefore(':'); val p = t.substringAfter(':', "").toIntOrNull() ?: def.port
                return Server(h, p, p != 587)
            }
            return ov(imapOverride, i) to ov(smtpOverride, s)
        }

        fun validAddr(a: String) = Regex("^[^@\\s<>\"]+@[^@\\s<>\"]+\\.[a-zA-Z]{2,}$").matches(a.trim())

        /** IMAP quoted string */
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** =?UTF-8?B?...?= / =?UTF-8?Q?...?= header शब्द खोलो */
        fun decodeHeader(raw: String): String {
            val unfolded = raw.replace(Regex("\\r?\\n[ \\t]+"), " ")
            val joined = unfolded.replace(Regex("\\?=\\s+=\\?"), "?==?")
            return Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").replace(joined) { m ->
                val cs = try { Charset.forName(m.groupValues[1]) } catch (_: Exception) { Charsets.UTF_8 }
                try {
                    val bytes = if (m.groupValues[2].equals("B", true)) Base64.getMimeDecoder().decode(m.groupValues[3])
                    else qp(m.groupValues[3].replace('_', ' '))
                    String(bytes, cs)
                } catch (_: Exception) { m.value }
            }.trim()
        }

        fun qp(s: String): ByteArray {
            val out = ByteArrayOutputStream()
            val t = s.replace(Regex("=\\r?\\n"), "")
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                val h = if (ch == '=' && i + 2 < t.length + 1 && i + 3 <= t.length) t.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (h != null) { out.write(h); i += 3; continue }
                out.write(ch.toString().toByteArray(Charsets.UTF_8)); i++
            }
            return out.toByteArray()
        }

        /** IMAP जवाब में marker के बाद वाला {n} literal (n बाइट; जवाब ISO-8859-1 में = 1 char 1 byte) */
        fun literalAfter(r: String, marker: String): String? {
            val at = r.indexOf(marker); if (at < 0) return null
            val m = Regex("\\{(\\d+)\\}\\n").find(r, at) ?: return null
            if (r.substring(at, m.range.first).contains('\n')) return null
            val start = m.range.last + 1
            val len = m.groupValues[1].toInt()
            return String(r.substring(start, minOf(r.length, start + len)).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }

        /** "Name: value" headers (folded भी) */
        fun headers(block: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            var last: String? = null
            for (line in block.split(Regex("\\r?\\n"))) {
                if (line.isEmpty()) continue
                if ((line[0] == ' ' || line[0] == '\t') && last != null) { m[last] = m[last] + " " + line.trim(); continue }
                val k = line.substringBefore(':', "").trim().lowercase()
                if (k.isEmpty()) continue
                m[k] = line.substringAfter(':').trim(); last = k
            }
            return m
        }

        private fun charsetOf(ct: String): Charset = Regex("charset=\"?([^\";\\s]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            ?.let { try { Charset.forName(it) } catch (_: Exception) { null } } ?: Charsets.UTF_8

        private fun decodeBody(body: String, cte: String, ct: String): String {
            val cs = charsetOf(ct)
            val raw = when (cte.trim().lowercase()) {
                "base64" -> try { String(Base64.getMimeDecoder().decode(body.replace(Regex("[^A-Za-z0-9+/=]"), "")), cs) } catch (_: Exception) { body }
                "quoted-printable" -> String(qp(body), cs)
                else -> body
            }
            return if (ct.lowercase().contains("html")) stripHtml(raw) else raw
        }

        fun stripHtml(h: String) = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

        /** सादा पाठ: multipart में पहला text/plain (न हो तो text/html), base64/QP खोलकर */
        fun plainText(ct: String, cte: String, body: String, depth: Int = 0): String {
            val b = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            if (b == null || depth > 3) return tidy(decodeBody(body, cte, ct))
            val parts = body.split("--$b").map { it.trimStart('\r', '\n') }.filter { it.isNotBlank() && !it.startsWith("--") }
            data class P(val ct: String, val cte: String, val body: String)
            val ps = parts.map { p ->
                val sep = Regex("\\r?\\n\\r?\\n").find(p)
                val h = headers(if (sep != null) p.substring(0, sep.range.first) else "")
                P(h["content-type"] ?: "text/plain", h["content-transfer-encoding"] ?: "", if (sep != null) p.substring(sep.range.last + 1) else p)
            }
            val pick = ps.firstOrNull { it.ct.lowercase().startsWith("text/plain") }
                ?: ps.firstOrNull { it.ct.lowercase().startsWith("multipart/") }
                ?: ps.firstOrNull { it.ct.lowercase().startsWith("text/html") }
                ?: return ""
            return plainText(pick.ct, pick.cte, pick.body, depth + 1)
        }

        fun tidy(s: String) = s.replace("\r", "").lines().map { it.trim() }.filterNot { it.startsWith(">") }
            .joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()

        /** "राम <ram@x.com>" -> "राम (ram@x.com)" */
        fun fromName(raw: String): String {
            val d = decodeHeader(raw)
            val a = Regex("<([^>]+)>").find(d)?.groupValues?.get(1)
            val n = d.replace(Regex("<[^>]+>"), "").replace("\"", "").trim()
            return when { a == null -> n; n.isBlank() -> a; else -> "$n ($a)" }
        }

        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        fun encHeader(s: String) = if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?${b64(s)}?="

        /** SMTP DATA का पूरा संदेश (UTF-8, base64 body) */
        fun message(from: String, to: String, subject: String, body: String, date: String, id: String): String = buildString {
            append("From: $from\r\nTo: $to\r\nSubject: ${encHeader(subject)}\r\nDate: $date\r\nMessage-ID: <$id>\r\n")
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(b64(body).chunked(76).joinToString("\r\n")); append("\r\n")
        }

        /** Live को ईमेल की सूची (छोटी) */
        fun summary(list: List<Msg>, maxChars: Int = 400): String =
            if (list.isEmpty()) "INBOX: no emails found." else "INBOX (newest first):\n" + list.joinToString("\n") { m ->
                "- [uid ${m.uid}]${if (m.unread) " (unread)" else ""} from ${m.from} • ${m.subject.ifBlank { "(no subject)" }} • ${m.date}\n  " +
                    m.text.replace(Regex("\\s+"), " ").take(maxChars)
            }
    }

    // ------------------------------------------------------------------ फ़ोन पर
    fun addr(c: Context) = Prefs.str(c, ADDR, "").trim()
    fun configured(c: Context) = Rules.validAddr(addr(c)) && Prefs.str(c, PASS, "").isNotBlank()

    private fun need(c: Context): Triple<String, String, Pair<Server, Server>> {
        val a = addr(c); val p = Prefs.str(c, PASS, "").replace(" ", "")
        if (!Rules.validAddr(a) || p.isBlank()) throw IllegalStateException("email not set up - Settings → DK कंप्यूटर → ईमेल में पता और App Password भरें")
        val s = Rules.servers(a, Prefs.str(c, IMAP_HOST, ""), Prefs.str(c, SMTP_HOST, "")) ?: throw IllegalStateException("bad email address")
        return Triple(a, p, s)
    }

    fun read(c: Context, count: Int, unreadOnly: Boolean): List<Msg> { val (a, p, s) = need(c); return Imap(s.first).use { it.login(a, p); it.recent(count.coerceIn(1, 10), unreadOnly) } }
    fun delete(c: Context, uid: Long) { val (a, p, s) = need(c); Imap(s.first).use { it.login(a, p); it.delete(uid) } }
    fun send(c: Context, to: String, subject: String, body: String) {
        val (a, p, s) = need(c)
        val sign = Prefs.str(c, SIGN, "").trim()
        Smtp.send(s.second, a, p, to.trim(), subject, if (sign.isBlank()) body else "$body\n\n$sign")
    }
    /** Settings का "जाँच" बटन: सिर्फ़ लॉगिन */
    fun test(c: Context): String { val (a, p, s) = need(c); Imap(s.first).use { it.login(a, p) }; return "IMAP ${s.first.host} ✓" }

    // ------------------------------------------------------------------ IMAP (बस ज़रूरी हिस्सा)
    private class Imap(srv: Server) : AutoCloseable {
        private val sock: Socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
            connect(InetSocketAddress(srv.host, srv.port), 15_000); soTimeout = 25_000; startHandshake()
        }
        private val inp = BufferedInputStream(sock.getInputStream())
        private val out: OutputStream = sock.getOutputStream()
        private var n = 0
        init { readLine(inp) }

        /** एक command, पूरा जवाब (literal {n} समेत) */
        fun cmd(c: String, log: String = c): String {
            val tag = "A" + (++n)
            out.write("$tag $c\r\n".toByteArray(Charsets.UTF_8)); out.flush()
            val sb = StringBuilder()
            while (true) {
                val line = readLine(inp) ?: throw IllegalStateException("IMAP connection closed")
                sb.append(line).append("\n")
                Regex("\\{(\\d+)\\}$").find(line)?.let { m -> sb.append(String(readN(inp, m.groupValues[1].toInt()), Charsets.ISO_8859_1)) }
                if (line.startsWith("$tag ")) {
                    if (!line.startsWith("$tag OK")) throw IllegalStateException("IMAP: " + (if (log != c) log else line.substringAfter(' ')).take(160))
                    return sb.toString()
                }
            }
        }

        fun login(a: String, p: String) {
            try { cmd("LOGIN ${Rules.q(a)} ${Rules.q(p)}", "LOGIN") } catch (e: Exception) {
                throw IllegalStateException("login failed - check the email and App Password (not the normal password)")
            }
        }

        fun recent(count: Int, unreadOnly: Boolean): List<Msg> {
            cmd("SELECT INBOX")
            val s = cmd(if (unreadOnly) "UID SEARCH UNSEEN" else "UID SEARCH ALL")
            val uids = s.lines().firstOrNull { it.startsWith("* SEARCH") }?.removePrefix("* SEARCH")?.trim()?.split(' ')
                ?.mapNotNull { it.toLongOrNull() }.orEmpty().takeLast(count).reversed()
            return uids.mapNotNull { u -> try { fetch(u) } catch (_: Exception) { null } }
        }

        private fun fetch(uid: Long): Msg {
            val r = cmd("UID FETCH $uid (FLAGS BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0.6000>)")
            val h = Rules.headers((Rules.literalAfter(r, "HEADER.FIELDS") ?: "").replace("\r", "").substringBefore("\n\n"))
            val text = Rules.literalAfter(r, "BODY[TEXT]") ?: ""
            return Msg(uid, Rules.fromName(h["from"] ?: ""),
                Rules.decodeHeader(h["subject"] ?: ""), h["date"] ?: "",
                Rules.plainText(h["content-type"] ?: "text/plain", h["content-transfer-encoding"] ?: "", text).take(1500),
                unread = !r.substringBefore("BODY").contains("\\Seen"))
        }

        fun delete(uid: Long) {
            cmd("SELECT INBOX")
            cmd("UID STORE $uid +FLAGS.SILENT (\\Deleted)")
            cmd("EXPUNGE")
        }

        override fun close() { try { cmd("LOGOUT") } catch (_: Exception) {}; try { sock.close() } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ SMTP
    private object Smtp {
        fun send(srv: Server, from: String, pass: String, to: String, subject: String, body: String) {
            if (!Rules.validAddr(to)) throw IllegalStateException("bad recipient address: $to")
            var sock: Socket = if (srv.tls) (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(srv.host, srv.port), 15_000); startHandshake()
            } else Socket().apply { connect(InetSocketAddress(srv.host, srv.port), 15_000) }
            sock.soTimeout = 25_000
            try {
                var inp = BufferedInputStream(sock.getInputStream()); var out = sock.getOutputStream()
                fun say(l: String?, ok: String, log: String = l ?: ""): String {
                    if (l != null) { out.write("$l\r\n".toByteArray(Charsets.UTF_8)); out.flush() }
                    var last: String
                    do { last = readLine(inp) ?: throw IllegalStateException("SMTP connection closed") } while (last.length > 3 && last[3] == '-')
                    if (!last.startsWith(ok)) throw IllegalStateException("SMTP ${log.take(20)}: ${last.take(160)}")
                    return last
                }
                say(null, "220")
                say("EHLO dk.local", "250")
                if (!srv.tls) {
                    say("STARTTLS", "220")
                    sock = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(sock, srv.host, srv.port, true).also { (it as SSLSocket).startHandshake() }
                    inp = BufferedInputStream(sock.getInputStream()); out = sock.getOutputStream()
                    say("EHLO dk.local", "250")
                }
                say("AUTH LOGIN", "334")
                say(Rules.b64(from), "334", "user")
                try { say(Rules.b64(pass), "235", "pass") } catch (_: Exception) { throw IllegalStateException("login failed - check the App Password") }
                say("MAIL FROM:<$from>", "250")
                say("RCPT TO:<$to>", "25")
                say("DATA", "354")
                val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
                val msg = Rules.message(from, to, subject, body, date, "${System.currentTimeMillis()}.dk@${from.substringAfter('@')}")
                // dot-stuffing (base64 में "." से शुरू लाइन नहीं होती, फिर भी)
                out.write(msg.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }.toByteArray(Charsets.UTF_8))
                say("\r\n.", "250")
                try { say("QUIT", "221") } catch (_: Exception) {}
            } finally { try { sock.close() } catch (_: Exception) {} }
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = i.read()
            if (x < 0) return if (b.size() == 0) null else String(b.toByteArray(), Charsets.ISO_8859_1)
            if (x == '\n'.code) break
            if (x != '\r'.code) b.write(x)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var o = 0
        while (o < n) { val r = i.read(buf, o, n - o); if (r < 0) break; o += r }
        return if (o == n) buf else buf.copyOf(o)
    }
}
