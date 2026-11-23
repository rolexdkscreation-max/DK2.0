package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक): होम के बीच में ORB या 3D अवतार - Settings › दिखावट › "DKAI का orb" से बदले.
 *
 *  - अवतार APK में नहीं आता: इंटरनेट से (GitHub repo का `avatars/` फ़ोल्डर) एक बार डाउनलोड, फिर फ़ोन में (filesDir/avatars) रहता है.
 *  - फ़ाइल न मिले / डाउनलोड न हो = कोई क्रैश नहीं, होम पर ORB.
 *  - सूची `avatars/index.json` से - बाद में 4-5 और लड़के-लड़कियाँ बस वहाँ एक पंक्ति जोड़कर, ऐप बदले बिना.
 *  - चुनाव pref [PREF] में ("orb" या "avatar:<id>") - ऐप दोबारा खोलने पर भी वही.
 */
object HomeAvatars {
    data class Avatar(val id: String, val name: String, val gender: String, val file: String)

    const val PREF = "home_center"
    const val ORB = "orb"
    private const val AV = "avatar:"
    const val BASE = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/main/avatars/"
    const val INDEX = "index.json"
    /** इससे बड़ी फ़ाइल नहीं (गलत / नुकसान वाली फ़ाइल से बचाव) */
    const val MAX_BYTES = 6 * 1024 * 1024

    /** नेट न हो तब भी Settings में कम से कम यह एक (मालिक की दी हुई तस्वीर) */
    val DEFAULTS = listOf(Avatar("dk_girl", "DK गर्ल", "girl", "dk_avatar_girl.png"))

    // ------------------------------------------------------------------ pure (JVM test)
    /** फ़ाइल का नाम सुरक्षित: सिर्फ़ a-z 0-9 _ - और .png (कोई / या .. नहीं) */
    fun safeFile(n: String?): Boolean = n != null && Regex("^[a-z0-9_\\-]{1,60}\\.png$").matches(n)

    fun safeId(n: String?): Boolean = n != null && Regex("^[a-z0-9_\\-]{1,40}$").matches(n)

    /** index.json -> सूची (गलत पंक्ति छोड़ो, एक id एक बार) */
    fun parse(json: String?): List<Avatar> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("avatars") ?: return emptyList()
            val out = LinkedHashMap<String, Avatar>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id"); val f = o.optString("file")
                if (!safeId(id) || !safeFile(f) || out.containsKey(id)) continue
                out[id] = Avatar(id, o.optString("name").ifBlank { id }, o.optString("gender").ifBlank { "girl" }, f)
            }
            out.values.toList()
        } catch (_: Exception) { emptyList() }
    }

    /** डाउनलोड वाली सूची + DEFAULTS (एक id एक बार, डाउनलोड वाली पहले) */
    fun merge(net: List<Avatar>): List<Avatar> = (net + DEFAULTS).distinctBy { it.id }

    /** pref का मान -> अवतार id (ORB = null) */
    fun idOf(pref: String?): String? = pref?.takeIf { it.startsWith(AV) }?.removePrefix(AV)?.takeIf { safeId(it) }

    fun prefFor(id: String?): String = if (id == null) ORB else AV + id

    /** PNG फ़ाइल की शुरुआत (8 बाइट) सही? */
    fun isPng(head: ByteArray): Boolean = head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
        head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()

    // ------------------------------------------------------------------ फ़ोन
    private fun sp(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.filesDir, "avatars").apply { mkdirs() }

    fun choice(c: Context): String = try { sp(c).getString(PREF, ORB) ?: ORB } catch (_: Exception) { ORB }
    fun chosenId(c: Context): String? = idOf(choice(c))
    fun chooseOrb(c: Context) { sp(c).edit().putString(PREF, ORB).apply(); version++ }
    fun chooseAvatar(c: Context, id: String) { sp(c).edit().putString(PREF, prefFor(id)).apply(); version++ }

    /** बदलाव गिनती - होम लौटते ही नया चुनाव दिखे */
    @Volatile var version = 0; private set

    @Volatile private var listMem: Pair<Long, List<Avatar>>? = null

    /** सूची (फ़ोन में रखी index.json + पहले से तय) - हर ताज़ा पर फ़ाइल दोबारा नहीं पढ़ते, बदली हो तभी */
    fun list(c: Context): List<Avatar> {
        val f = File(dir(c), INDEX)
        val stamp = if (f.exists()) f.lastModified() else 0L
        listMem?.let { if (it.first == stamp) return it.second }
        val l = merge(parse(try { f.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }))
        listMem = stamp to l
        return l
    }

    fun byId(c: Context, id: String?): Avatar? = list(c).firstOrNull { it.id == id }

    fun file(c: Context, a: Avatar): File = File(dir(c), a.file)

    fun downloaded(c: Context, a: Avatar): Boolean = file(c, a).let { it.exists() && it.length() > 64 }

    private var memKey: String? = null
    private var mem: Bitmap? = null

    /**
     * अवतार की तस्वीर: पहले फ़ोन में डाउनलोड वाली; न हो तो res/drawable-nodpi में उसी नाम की (अगर कभी डाली गई);
     * कुछ नहीं = null (होम पर ORB). कभी क्रैश नहीं.
     */
    fun bitmap(c: Context, a: Avatar, maxPx: Int): Bitmap? {
        val f = file(c, a)
        val key = a.file + "|" + f.lastModified() + "|" + maxPx
        if (key == memKey) mem?.let { return it }
        val b = try {
            if (f.exists()) decode(maxPx) { o -> BitmapFactory.decodeFile(f.path, o) }
            else {
                val res = c.resources.getIdentifier(a.file.removeSuffix(".png"), "drawable", c.packageName)
                if (res != 0) decode(maxPx) { o -> BitmapFactory.decodeResource(c.resources, res, o) } else null
            }
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
        if (b != null) { memKey = key; mem = b }
        return b
    }

    private fun decode(maxPx: Int, go: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        go(o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        return go(BitmapFactory.Options().apply { inSampleSize = s })
    }

    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** इंटरनेट से: नई सूची + चुना अवतार (पहले से हो तो दोबारा नहीं). [done] UI thread पर (true = तस्वीर तैयार) */
    fun fetch(c: Context, id: String?, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        val key = id ?: "-"
        if (!busy.add(key)) return
        Thread {
            var ok = false
            try {
                refreshIndex(app)
                val a = byId(app, id)
                ok = a != null && (downloaded(app, a) || download(app, a))
            } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) } finally { busy.remove(key) }
            main.post { try { done(ok) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        }.start()
    }

    private fun refreshIndex(c: Context) {
        val bytes = get(BASE + INDEX, 256 * 1024) ?: return
        val txt = String(bytes, Charsets.UTF_8)
        if (parse(txt).isEmpty()) return
        val f = File(dir(c), INDEX)
        File(dir(c), "$INDEX.tmp").apply { writeText(txt); renameTo(f) }
    }

    private fun download(c: Context, a: Avatar): Boolean {
        if (!safeFile(a.file)) return false
        val bytes = get(BASE + a.file, MAX_BYTES) ?: return false
        if (!isPng(bytes)) return false
        val tmp = File(dir(c), a.file + ".tmp")
        tmp.writeBytes(bytes)
        // सच में खुलने वाली तस्वीर है? तभी रखो
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, o)
        if (o.outWidth <= 0) { tmp.delete(); return false }
        return tmp.renameTo(file(c, a))
    }

    private fun get(url: String, max: Int): ByteArray? {
        val con = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 20000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DK-Assistant")
        }
        return try {
            if (con.responseCode !in 200..299) return null
            val out = java.io.ByteArrayOutputStream()
            con.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); if (out.size() > max) return null
                }
            }
            out.toByteArray()
        } finally { con.disconnect() }
    }
}
