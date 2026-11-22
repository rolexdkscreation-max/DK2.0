package com.dk.assistant.livewall

import org.json.JSONObject

/**
 * 22 नवंबर 2026 (मालिक: "लाइव वॉलपेपर अभी एक ही जोड़ो, बाद में जितना ऐड करना है उतना; इंटरनेट से डाउनलोड
 * होने वाला, ऐप के अंदर नहीं डालना"): वॉलपेपर की सूची repo के `wallpapers/catalog.json` में. नया वॉलपेपर =
 * बस वहाँ एक entry + उसकी फ़ाइलें (ऐप का नया version ज़रूरी नहीं). हर फ़ाइल SHA-256 और आकार से जाँची जाती है.
 * शुद्ध Kotlin + org.json (JUnit से जाँचा जाता है).
 */
object SkyCatalog {
    /** इस ऐप का दृश्य-इंजन कितना नया है - catalog की entry इससे नया माँगे तो "ऐप अपडेट करें" */
    const val ENGINE = 1
    const val MAX_FILE = 8 * 1024 * 1024
    const val MAX_TOTAL = 20 * 1024 * 1024

    private const val RAW = "https://raw.githubusercontent.com/rolexdkscreation-max/DK2.0/"
    const val CATALOG = RAW + "main/wallpapers/catalog.json"
    const val CATALOG_BRANCH = RAW + "feature/dk-live-wallpaper/wallpapers/catalog.json"

    data class FileRef(val name: String, val url: String, val sha256: String, val bytes: Long)

    data class Item(
        val id: String,
        val engine: String,
        val minEngine: Int,
        val version: Int,
        val title: String,
        val sub: String,
        val credits: String,
        val files: List<FileRef>,
    ) {
        val totalBytes: Long get() = files.sumOf { it.bytes }
        val supported: Boolean get() = engine == "sky" && minEngine <= ENGINE
    }

    private val ID_RE = Regex("^[a-z0-9_]{2,40}$")
    private val NAME_RE = Regex("^[a-z0-9_]{1,40}\\.(webp|png|jpg|csv|json)$")
    private val SHA_RE = Regex("^[0-9a-f]{64}$")

    fun validId(id: String?) = id != null && ID_RE.matches(id)
    fun validName(n: String?) = n != null && NAME_RE.matches(n)

    /** सिर्फ़ हमारे GitHub repo से (कोई अनजान सर्वर नहीं) */
    fun trustedUrl(u: String) = u.startsWith(RAW) || u.startsWith("https://github.com/rolexdkscreation-max/DK2.0/")

    fun parse(json: String): List<Item> = try {
        val arr = JSONObject(json).optJSONArray("items")
        val out = ArrayList<Item>()
        if (arr != null) for (i in 0 until arr.length()) parseItem(arr.optJSONObject(i))?.let { it2 -> if (out.none { it.id == it2.id }) out += it2 }
        out
    } catch (_: Exception) { emptyList() }

    fun parseItem(o: JSONObject?): Item? {
        if (o == null) return null
        val id = o.optString("id")
        if (!validId(id)) return null
        val fa = o.optJSONArray("files") ?: return null
        val files = ArrayList<FileRef>()
        for (i in 0 until fa.length()) {
            val f = fa.optJSONObject(i) ?: return null
            val name = f.optString("name"); val url = f.optString("url"); val sha = f.optString("sha256").lowercase()
            val bytes = f.optLong("bytes", -1)
            if (!validName(name) || !trustedUrl(url) || !SHA_RE.matches(sha) || bytes <= 0 || bytes > MAX_FILE) return null
            if (files.any { it.name == name }) return null
            files += FileRef(name, url, sha, bytes)
        }
        if (files.none { it.name == "scene.json" }) return null
        val item = Item(
            id = id, engine = o.optString("engine", "sky"), minEngine = o.optInt("minEngine", 1),
            version = o.optInt("version", 1).coerceIn(1, 9999),
            title = o.optString("title", id).take(48).ifBlank { id }, sub = o.optString("sub", "").take(120),
            credits = o.optString("credits", "").take(300), files = files,
        )
        return if (item.totalBytes > MAX_TOTAL) null else item
    }

    /** उसी फ़ाइल का दूसरा पता (main पर न मिले तो feature branch) */
    fun fallbackUrl(u: String): String? =
        if (u.startsWith(RAW + "main/")) RAW + "feature/dk-live-wallpaper/" + u.removePrefix(RAW + "main/") else null

    /** scene.json -> SkyPack (गलत values सुरक्षित सीमा में) */
    fun parseScene(json: String): SkyPack? = try {
        val o = JSONObject(json)
        val la = o.optJSONArray("layers")
        val layers = ArrayList<SkyPack.Layer>()
        if (la != null) for (i in 0 until la.length()) {
            val l = la.optJSONObject(i) ?: continue
            val id = l.optString("id"); val file = l.optString("file")
            val kind = l.optString("kind", "lit")
            if (!validId(id) || id.startsWith("_") || !validName(file) || kind !in listOf("haze", "lit", "near")) continue
            layers += SkyPack.Layer(
                id = id, file = file, kind = kind,
                width = l.optDouble("width", 1.25).toFloat().coerceIn(1f, 2f),
                bottom = l.optDouble("bottom", 1.0).toFloat().coerceIn(0.3f, 1.2f),
                height = l.optDouble("height", 0.0).toFloat().coerceIn(0f, 1.5f),
                parallax = l.optDouble("parallax", 0.0).toFloat().coerceIn(0f, 0.2f),
                haze = l.optDouble("haze", 0.0).coerceIn(0.0, 0.9),
                reflect = l.optBoolean("reflect", false),
            )
        }
        SkyPack(
            horizon = o.optDouble("horizon", 0.62).toFloat().coerceIn(0.4f, 0.8f),
            sunSize = o.optDouble("sunSize", 0.042).toFloat().coerceIn(0.01f, 0.1f),
            moonSize = o.optDouble("moonSize", 0.06).toFloat().coerceIn(0.01f, 0.12f),
            layers = layers,
            moon = o.optString("moon", "moon.jpg").takeIf { validName(it) } ?: "moon.jpg",
            stars = o.optString("stars", "stars.csv").takeIf { validName(it) } ?: "stars.csv",
        )
    } catch (_: Exception) { null }
}
