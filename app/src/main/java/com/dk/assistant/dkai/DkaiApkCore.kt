package com.dk.assistant.dkai

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 14 नवंबर 2026 (मालिक): "Terminal से कैलकुलेटर ऐप बनाओ - वो नहीं बना पाया. ऐप बनाए और इंस्टॉल करने लायक बनाए".
 *
 * जड़ (root cause): DKAI Terminal फ़ोन का साधारण sh/toybox है - उसमें javac / kotlinc / gradle / aapt2 / d8 / apksigner
 * कुछ नहीं, इसलिए कोई भी ऐप (APK) बन ही नहीं सकता था; AI सिर्फ़ फ़ाइलें लिखकर अटक जाता था.
 *
 * हल: बिना किसी build tool के फ़ोन पर ही असली APK. DK अपने assets में एक छोटा WebView साँचा (`:apkshell`, ~6 KB) रखता है.
 * यहाँ (pure JVM, tests से जाँचा):
 *  1. साँचे के binary AndroidManifest.xml की string pool में package नाम और ऐप का नाम बदलो ([patchManifest]);
 *  2. साँचे की बाकी फ़ाइलें + आपकी HTML/CSS/JS को assets/www/ में रखकर नई zip ([assemble]);
 *  3. फ़ोन की अपनी key से APK Signature Scheme v2 sign ([sign], apksig) - Android इसे सीधे इंस्टॉल करता है.
 */
object DkaiApkCore {
    /** apkshell/build.gradle.kts का applicationId */
    const val PLACEHOLDER_PKG = "com.dk.apkshell.placeholder"
    /** apkshell/src/main/AndroidManifest.xml का android:label */
    const val PLACEHOLDER_LABEL = "DK_APP_LABEL_PLACEHOLDER"
    const val WWW = "assets/www/"
    const val PKG_PREFIX = "com.dkapp."
    const val MIN_SDK = 26
    const val MAX_TOTAL = 80L * 1024 * 1024

    // ------------------------------------------------------------------ नाम
    /** ऐप का नाम -> पक्का package नाम (वही नाम दोबारा = वही package = पुराने के ऊपर अपडेट) */
    fun packageFor(name: String): String {
        val ascii = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "").take(28)
        val crc = CRC32().apply { update(name.trim().lowercase(Locale.ROOT).toByteArray()) }.value
        val tail = when {
            ascii.isEmpty() -> "app" + java.lang.Long.toHexString(crc)
            ascii[0].isDigit() -> "a$ascii"
            else -> ascii
        }
        return PKG_PREFIX + tail
    }

    private val FILLER = listOf("टर्मिनल से", "terminal se", "terminal से", "इंस्टॉल करने लायक", "install karne layak", "मेरे लिए", "mere liye",
        "बना दो", "बनादो", "बनाओ", "बना के दो", "बनाकर दो", "बनाइए", "बनाना है", "बनाना", "banao", "bana do", "banado", "please", "प्लीज़", "प्लीज")
    private val FILLER_WORDS = setOf("एक", "ek", "a", "an", "the", "make", "create", "build", "me", "for")

    /** "एक कैलकुलेटर ऐप बनाओ" -> "कैलकुलेटर ऐप" (ऐप का नाम; पहला अक्षर बड़ा) */
    fun appName(request: String): String {
        var t = " " + request.replace(Regex("[\\p{Cntrl}]"), " ") + " "
        for (f in FILLER) t = t.replace(Regex("(?i)(?<=\\s)" + Regex.escape(f) + "(?=[\\s,.!।]|$)"), " ")
        val w = t.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in FILLER_WORDS }
        val n = w.joinToString(" ").trim(' ', ',', '.', '!', '।').take(30).trim()
        return n.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }.ifBlank { labelFor(request) }
    }

    /** स्क्रीन पर ऐप का नाम: साफ़, छोटा; खाली = "DK App" */
    fun labelFor(name: String): String =
        name.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(30).ifBlank { "DK App" }

    /** APK फ़ाइल का नाम */
    fun fileFor(name: String): String = packageFor(name).removePrefix(PKG_PREFIX) + ".apk"

    /** बनाने लायक फ़ोल्डर? index.html सीधे, या App Studio के नेटिव project का preview/index.html. null = नहीं */
    fun webRoot(dir: File): File? = when {
        File(dir, "index.html").isFile -> dir
        File(dir, "preview/index.html").isFile -> File(dir, "preview")
        File(dir, "www/index.html").isFile -> File(dir, "www")
        else -> null
    }

    /** ऐप में जाने वाली फ़ाइलें (छिपी / DK की अपनी / पुराने APK नहीं) */
    fun webFiles(root: File): List<Pair<String, File>> = root.walkTopDown()
        .onEnter { it == root || !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".apk") && it.name != DkaiCore.REPO_META }
        .map { it.relativeTo(root).path.replace('\\', '/') to it }
        .sortedBy { it.first }.toList()

    // ------------------------------------------------------------------ binary XML (AXML) की string pool
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 0x100

    /** manifest की सारी strings (जाँच / tests के लिए) */
    fun manifestStrings(axml: ByteArray): List<String> = readPool(axml).strings

    private class Pool(val start: Int, val headerSize: Int, val size: Int, val flags: Int, val strings: List<String>)

    private fun readPool(axml: ByteArray): Pool {
        val b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        require(axml.size > 16 && (b.getShort(0).toInt() and 0xffff) == RES_XML_TYPE) { "AndroidManifest.xml binary XML नहीं" }
        val start = b.getShort(2).toInt() and 0xffff
        require((b.getShort(start).toInt() and 0xffff) == RES_STRING_POOL_TYPE) { "string pool नहीं मिली" }
        val hs = b.getShort(start + 2).toInt() and 0xffff
        val size = b.getInt(start + 4)
        val count = b.getInt(start + 8)
        val styles = b.getInt(start + 12)
        val flags = b.getInt(start + 16)
        val strStart = b.getInt(start + 20)
        require(styles == 0) { "styled strings वाला manifest नहीं संभलता" }
        val utf8 = flags and UTF8_FLAG != 0
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = start + strStart + b.getInt(start + hs + 4 * i)
            if (utf8) {
                // UTF-16 लंबाई (छोड़ो), फिर byte लंबाई, फिर bytes
                p += if (axml[p].toInt() and 0x80 != 0) 2 else 1
                var n = axml[p].toInt() and 0xff
                if (n and 0x80 != 0) { n = ((n and 0x7f) shl 8) or (axml[p + 1].toInt() and 0xff); p += 2 } else p += 1
                out.add(String(axml, p, n, Charsets.UTF_8))
            } else {
                var n = b.getShort(p).toInt() and 0xffff
                if (n and 0x8000 != 0) { n = ((n and 0x7fff) shl 16) or (b.getShort(p + 2).toInt() and 0xffff); p += 4 } else p += 2
                out.add(String(axml, p, n * 2, Charsets.UTF_16LE))
            }
        }
        return Pool(start, hs, size, flags, out)
    }

    /**
     * string pool दोबारा लिखो: हर string पर [change] (क्रम / गिनती वही - XML के बाकी हिस्से index से जुड़े रहते हैं),
     * फिर chunk और फ़ाइल का size ठीक.
     */
    fun patchManifest(axml: ByteArray, change: (String) -> String): ByteArray {
        val pool = readPool(axml)
        val utf8 = pool.flags and UTF8_FLAG != 0
        val data = ByteArrayOutputStream()
        val offsets = IntArray(pool.strings.size)
        for ((i, s0) in pool.strings.withIndex()) {
            val s = change(s0)
            offsets[i] = data.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                fun len8(n: Int) { if (n > 0x7f) { data.write(((n shr 8) and 0x7f) or 0x80); data.write(n and 0xff) } else data.write(n) }
                require(s.length < 0x7fff && bytes.size < 0x7fff) { "string बहुत लंबी" }
                len8(s.length); len8(bytes.size); data.write(bytes); data.write(0)
            } else {
                val n = s.length
                require(n < 0x7fff) { "string बहुत लंबी" }
                data.write(n and 0xff); data.write((n shr 8) and 0xff)
                data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val strStart = pool.headerSize + 4 * offsets.size
        val newSize = strStart + data.size()
        val head = ByteBuffer.allocate(strStart).order(ByteOrder.LITTLE_ENDIAN)
        head.put(axml, pool.start, pool.headerSize)
        head.putInt(4, newSize)
        head.putInt(16, pool.flags and 0x1.inv())     // क्रम (sorted) अब पक्का नहीं
        head.putInt(20, strStart)
        head.putInt(24, 0)                            // styles नहीं
        head.position(pool.headerSize)
        offsets.forEach { head.putInt(it) }
        val rest = axml.copyOfRange(pool.start + pool.size, axml.size)
        val out = ByteBuffer.allocate(pool.start + newSize + rest.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(axml, 0, pool.start)
        out.put(head.array()); out.put(data.toByteArray()); out.put(rest)
        out.putInt(4, out.capacity())
        return out.array()
    }

    /** साँचे का manifest -> इस ऐप का (package + नाम) */
    fun manifestFor(axml: ByteArray, pkg: String, label: String): ByteArray {
        require(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg)) { "गलत package नाम: $pkg" }
        val names = manifestStrings(axml)
        require(PLACEHOLDER_PKG in names && PLACEHOLDER_LABEL in names) { "साँचे के manifest में placeholder नहीं" }
        return patchManifest(axml) { s ->
            when {
                s == PLACEHOLDER_PKG -> pkg
                s.startsWith("$PLACEHOLDER_PKG.") -> pkg + s.removePrefix(PLACEHOLDER_PKG)
                s == PLACEHOLDER_LABEL -> label
                else -> s
            }
        }
    }

    // ------------------------------------------------------------------ zip
    /** साँचा + वेब फ़ाइलें -> बिना sign वाली APK. return = ऐप में गई वेब फ़ाइलों की गिनती */
    fun assemble(template: File, web: File, pkg: String, label: String, out: File): Int {
        val files = webFiles(web)
        require(files.any { it.first == "index.html" }) { "index.html नहीं मिली" }
        require(files.sumOf { it.second.length() } <= MAX_TOTAL) { "फ़ाइलें 80 MB से ज़्यादा" }
        out.parentFile?.mkdirs()
        ZipFile(template).use { zf ->
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                for (e in zf.entries().asSequence()) {
                    val n = e.name
                    if (e.isDirectory || n.startsWith(WWW) || (n.startsWith("META-INF/") && Regex("\\.(SF|RSA|DSA|EC|MF)$").containsMatchIn(n))) continue
                    var bytes = zf.getInputStream(e).use { it.readBytes() }
                    if (n == "AndroidManifest.xml") bytes = manifestFor(bytes, pkg, label)
                    put(z, n, bytes, stored = e.method == ZipEntry.STORED && n != "AndroidManifest.xml")
                }
                for ((path, f) in files) put(z, WWW + path, f.readBytes(), stored = false)
            }
        }
        return files.size
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val e = ZipEntry(name).apply { time = 315_532_800_000L }   // 1980 - हर बार एक जैसा
        if (stored) {
            e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().apply { update(bytes) }.value
        } else e.method = ZipEntry.DEFLATED
        z.putNextEntry(e); z.write(bytes); z.closeEntry()
    }

    // ------------------------------------------------------------------ फ़ोन की अपनी sign key
    class Key(val priv: PrivateKey, val cert: X509Certificate)

    /** [dir] में key हो तो वही (वही key = ऐप का अपडेट), नहीं तो नई RSA-2048 + self-signed प्रमाणपत्र */
    fun keyIn(dir: File): Key {
        val kf = File(dir, "dk_apps_key.p8"); val cf = File(dir, "dk_apps_cert.der")
        if (kf.isFile && cf.isFile) try {
            val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream()) as X509Certificate
            return Key(priv, cert)
        } catch (_: Exception) {}
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val der = selfSigned(kp.private, kp.public.encoded, "DK Apps", System.currentTimeMillis())
        dir.mkdirs()
        kf.writeBytes(kp.private.encoded); cf.writeBytes(der)
        kf.setReadable(false, false); kf.setReadable(true, true)
        return Key(kp.private, CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate)
    }

    /** छोटा DER लिखने वाला - self-signed X.509 v3 (SHA256withRSA), 30 साल चले */
    fun selfSigned(priv: PrivateKey, spki: ByteArray, cn: String, now: Long): ByteArray {
        val alg = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, cn.toByteArray(Charsets.UTF_8)))))
        val from = Date(now - 24L * 3600 * 1000); val to = Date(now + 30L * 365 * 24 * 3600 * 1000)
        fun time(d: Date): ByteArray {
            val year = SimpleDateFormat("yyyy", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toInt()
            val gen = year >= 2050
            val s = SimpleDateFormat(if (gen) "yyyyMMddHHmmss'Z'" else "yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
            return tlv(if (gen) 0x18 else 0x17, s.toByteArray(Charsets.US_ASCII))
        }
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(tlv(0xa0, int(BigInteger.valueOf(2))), int(serial), alg, name, seq(time(from), time(to)), name, spki)
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(priv); update(tbs); sign() }
        return seq(tbs, alg, tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun tlv(tag: Int, v: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(tag)
        val n = v.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xff) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xff); o.write(n and 0xff) }
        }
        o.write(v); return o.toByteArray()
    }
    private fun seq(vararg p: ByteArray) = tlv(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg p: ByteArray) = tlv(0x31, p.fold(ByteArray(0)) { a, b -> a + b })
    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun oid(s: String): ByteArray {
        val p = s.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream(); o.write((p[0] * 40 + p[1]).toInt())
        for (x in p.drop(2)) {
            val parts = mutableListOf<Int>(); var v = x
            do { parts.add(0, (v and 0x7f).toInt()); v = v shr 7 } while (v > 0)
            parts.forEachIndexed { i, b -> o.write(if (i < parts.size - 1) b or 0x80 else b) }
        }
        return tlv(0x06, o.toByteArray())
    }

    // ------------------------------------------------------------------ sign
    /** APK Signature Scheme v2 (Android 7+ ; DK खुद Android 8+). apksig खुद STORED फ़ाइलों को 4-byte पर align करता है */
    fun sign(unsigned: File, out: File, key: Key) {
        val cfg = com.android.apksig.ApkSigner.SignerConfig.Builder("DKAPPS", key.priv, listOf(key.cert)).build()
        com.android.apksig.ApkSigner.Builder(listOf(cfg))
            .setInputApk(unsigned).setOutputApk(out)
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false)
            .setCreatedBy("DK")
            .build().sign()
    }

    data class Built(val apk: File, val pkg: String, val label: String, val files: Int)

    /** पूरा काम: [web] फ़ोल्डर (index.html वाला) -> [outDir]/<नाम>.apk, sign किया हुआ */
    fun build(template: File, web: File, name: String, outDir: File, keyDir: File, tmp: File): Built {
        val pkg = packageFor(name); val label = labelFor(name)
        outDir.mkdirs(); tmp.mkdirs()
        val unsigned = File(tmp, "dk_unsigned_${System.nanoTime()}.apk")
        val out = File(outDir, fileFor(name))
        try {
            val n = assemble(template, web, pkg, label, unsigned)
            val part = File(outDir, out.name + ".part")
            sign(unsigned, part, keyIn(keyDir))
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) { part.copyTo(out, true); part.delete() }
            return Built(out, pkg, label, n)
        } finally { unsigned.delete() }
    }
}
