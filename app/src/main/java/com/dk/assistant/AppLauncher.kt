package com.dk.assistant

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Apps kholna aur seedhe intents (YouTube search, Google search, URL, dialer). */
object AppLauncher {

    private val aliases = mapOf(
        "gallery" to listOf("gallery", "photos", "गैलरी", "album"),
        "camera" to listOf("camera", "कैमरा"),
        "whatsapp" to listOf("whatsapp", "व्हाट्सएप"),
        "youtube" to listOf("youtube", "यूट्यूब"),
        "facebook" to listOf("facebook", "फेसबुक"),
        "settings" to listOf("settings", "सेटिंग्स"),
        "chrome" to listOf("chrome", "browser", "क्रोम", "ब्राउज़र"),
        "flipkart" to listOf("flipkart", "फ्लिपकार्ट", "फ़्लिपकार्ट"),
        "amazon" to listOf("amazon", "अमेज़न", "अमेजन", "अमेज़ॉन", "अमेजॉन"),
        "meesho" to listOf("meesho", "मीशो", "मेसो", "मिशो"),
        "zepto" to listOf("zepto", "ज़ेप्टो", "जेप्टो", "जैप्टो"),
        "blinkit" to listOf("blinkit", "ब्लिंकिट", "ब्लिंकट", "ब्लिंक इट"),
        "instamart" to listOf("instamart", "इंस्टामार्ट", "इन्स्टामार्ट"),
        "swiggy" to listOf("swiggy", "स्विगी", "सुकी", "स्वीगी"),
        "zomato" to listOf("zomato", "ज़ोमैटो", "जोमैटो"),
        "bigbasket" to listOf("bigbasket", "बिगबास्केट", "बिग बास्केट"),
        "jiomart" to listOf("jiomart", "जियोमार्ट", "जियो मार्ट"),
        "myntra" to listOf("myntra", "मिंत्रा", "मंत्रा"),
        "ajio" to listOf("ajio", "अजियो"),
        "phonepe" to listOf("phonepe", "फोनपे", "फ़ोनपे"),
        "paytm" to listOf("paytm", "पेटीएम"),
        "gpay" to listOf("google pay", "gpay", "गूगल पे", "जीपे"),
        "instagram" to listOf("instagram", "इंस्टाग्राम", "इंस्टा"),
        "telegram" to listOf("telegram", "टेलीग्राम"),
        "maps" to listOf("maps", "मैप", "नक्शा"),
        "playstore" to listOf("play store", "प्ले स्टोर"),
        "calculator" to listOf("calculator", "कैलकुलेटर"),
        "clock" to listOf("clock", "घड़ी", "अलार्म"),
        "contacts" to listOf("contacts", "कॉन्टैक्ट"),
        "messages" to listOf("messages", "मैसेज", "एसएमएस"),
        "phone" to listOf("phone", "फोन", "डायलर"),
        "spotify" to listOf("spotify", "स्पॉटिफाई")
    )

    /** Label na mile to in package names se kholo. */
    private val packages = mapOf(
        "flipkart" to listOf("com.flipkart.android"),
        "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "meesho" to listOf("com.meesho.supply"),
        "zepto" to listOf("com.zeptoconsumerapp"),
        "blinkit" to listOf("com.grofers.customerapp"),
        "instamart" to listOf("in.swiggy.android.instamart", "in.swiggy.android"),
        "swiggy" to listOf("in.swiggy.android"),
        "zomato" to listOf("com.application.zomato"),
        "bigbasket" to listOf("com.bigbasket.mobileapp"),
        "jiomart" to listOf("com.jpl.jiomart"),
        "myntra" to listOf("com.myntra.android"),
        "ajio" to listOf("com.ril.ajio"),
        "phonepe" to listOf("com.phonepe.app"),
        "paytm" to listOf("net.one97.paytm"),
        "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "instagram" to listOf("com.instagram.android"),
        "youtube" to listOf("com.google.android.youtube"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "maps" to listOf("com.google.android.apps.maps"),
        "playstore" to listOf("com.android.vending"),
        "chrome" to listOf("com.android.chrome")
    )

    private fun groupOf(q: String): String? =
        aliases.entries.firstOrNull { (_, words) -> words.any { q.contains(it) } }?.key

    private fun launchPkg(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true
    }

    /** Shopping/grocery app me seedha search (jahan link se ho sake), warna app khol do. */
    fun shopSearch(ctx: Context, app: String, q: String): String {
        val g = groupOf(app.lowercase()) ?: app.lowercase()
        val e = Uri.encode(q.trim())
        val url = when (g) {
            "flipkart" -> "https://www.flipkart.com/search?q=$e"
            "amazon" -> "https://www.amazon.in/s?k=$e"
            "meesho" -> "https://www.meesho.com/search?q=$e"
            "myntra" -> "https://www.myntra.com/$e"
            "ajio" -> "https://www.ajio.com/search/?text=$e"
            "blinkit" -> "https://blinkit.com/s/?q=$e"
            "zepto" -> "https://www.zeptonow.com/search?query=$e"
            "bigbasket" -> "https://www.bigbasket.com/ps/?q=$e"
            "jiomart" -> "https://www.jiomart.com/search/$e"
            else -> null
        }
        if (url != null) {
            for (pkg in packages[g].orEmpty()) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "opened $g app search for '$q' (screen dekh kar result check karo)"
                } catch (_: ActivityNotFoundException) {
                } catch (_: Exception) {}
            }
        }
        val r = open(ctx, app)
        return if (r.startsWith("ERROR")) r
        else "$r - ab app ke search box par click karke '$q' type karo aur search karo"
    }

    /** v3.33: "मेरा shopping app खोलो" - फ़ोन में इस किस्म के जो ऐप हैं (package पक्के) */
    private val categories = mapOf(
        "shopping" to Pair(listOf("shopping", "शॉपिंग", "शोपिंग", "shop"), listOf("flipkart", "amazon", "meesho", "myntra", "ajio")),
        "grocery" to Pair(listOf("grocery", "ग्रॉसरी", "ग्रोसरी", "राशन", "kirana", "किराना", "सब्ज़ी", "सब्जी"), listOf("blinkit", "zepto", "instamart", "bigbasket", "jiomart")),
        "food" to Pair(listOf("food", "फूड", "खाना", "khana"), listOf("swiggy", "zomato")),
        "payment" to Pair(listOf("payment", "पेमेंट", "upi", "यूपीआई", "पैसे"), listOf("phonepe", "gpay", "paytm")),
        "music" to Pair(listOf("music", "म्यूजिक", "म्यूज़िक", "गाने वाला", "गानों वाला"), listOf("spotify", "ytmusic", "gaana", "jiosaavn", "wynk"))
    )
    private val extraPkgs = mapOf(
        "spotify" to listOf("com.spotify.music"), "ytmusic" to listOf("com.google.android.apps.youtube.music"),
        "gaana" to listOf("com.gaana"), "jiosaavn" to listOf("com.jio.media.jiobeats"), "wynk" to listOf("com.bsbportal.music")
    )

    /** आख़िरी बार खोले ऐप का package - खुलने की जाँच (foreground) के लिए */
    @Volatile var lastPkg: String = ""
        private set

    /** "the phone app" / "मेरा शॉपिंग ऐप" -> "phone" / "शॉपिंग" */
    fun cleanName(name: String): String = name.trim().lowercase()
        .replace(Regex("^(?:the|my|मेरा|मेरे|मेरी|अपना|अपने)\\s+"), "")
        .replace(Regex("\\s+(?:app|ऐप|एप|application|एप्लिकेशन|वाला ऐप|वाला)$"), "")
        .trim()

    private fun installedLabel(pm: android.content.pm.PackageManager, pkg: String): String? = try {
        if (pm.getLaunchIntentForPackage(pkg) == null) null
        else pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { null }

    private fun launchMatch(ctx: Context, label: String, pkg: String): String {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "ERROR_LAUNCH: $label फ़ोन में है पर खुल नहीं सकता (इसका कोई launcher screen नहीं)।"
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        lastPkg = pkg
        return "opened $label"
    }

    fun open(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val query = cleanName(name).ifBlank { name.trim().lowercase() }

        // Agar seedha package name diya hai (com.xyz.app)
        if (query.contains('.') && !query.contains(' ')) {
            pm.getLaunchIntentForPackage(query)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lastPkg = query
                return "opened $query"
            }
        }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }
        val candidates = mutableListOf(query)
        aliases.forEach { (_, words) -> if (words.any { query.contains(it) }) candidates.addAll(words) }

        // 1) नाम बिल्कुल वही
        candidates.firstNotNullOfOrNull { c -> apps.firstOrNull { it.first.lowercase() == c } }?.let { return launchMatch(ctx, it.first, it.second) }
        // 1b) किस्म ("shopping app") - फ़ोन में एक हो तो वही, कई हों तो पूछो, न हो तो साफ़ बताओ
        categories.entries.firstOrNull { (_, v) -> v.first.any { query == it || query == "$it apps" } }?.let { (cat, v) ->
            val found = v.second.mapNotNull { g ->
                (packages[g].orEmpty() + extraPkgs[g].orEmpty()).firstNotNullOfOrNull { p -> installedLabel(pm, p)?.let { it to p } }
            }.distinctBy { it.second }
            return when (found.size) {
                0 -> "ERROR_NOT_INSTALLED: फ़ोन में कोई $cat ऐप नहीं मिला।"
                1 -> launchMatch(ctx, found[0].first, found[0].second)
                else -> "ASK: फ़ोन में कई $cat ऐप हैं: ${found.joinToString(", ") { it.first }}। कौन सा खोलूँ?"
            }
        }

        // 2) आवाज़ से मिलता नाम - हिंदी में बोला, फ़ोन में अंग्रेज़ी ("स्नैपचैट" = Snapchat) - हर ऐप, सिर्फ़ YouTube/Facebook नहीं
        val near = NameMatch.best(query, apps, { it.first }, min = 60)
        if (near.size == 1) return launchMatch(ctx, near[0].first, near[0].second)
        if (groupOf(query) == null && near.size in 2..4 && near.map { it.first.lowercase() }.distinct().size > 1)
            return "ASK: \"$name\" नाम से कई ऐप मिले: ${near.joinToString(", ") { it.first }}। कौन सा खोलूँ?"
        // 3) पुराना ढीला मिलान (नाम का हिस्सा) + पक्के package
        val match = candidates.firstNotNullOfOrNull { c ->
                apps.firstOrNull { it.first.lowercase().contains(c) || (it.first.length > 2 && c.contains(it.first.lowercase())) }
            }
            ?: near.firstOrNull()
            ?: groupOf(query)?.let { g -> packages[g]?.firstOrNull { launchPkg(ctx, it) }?.let { lastPkg = it; return "opened $g" } }
            ?: return "ERROR_NOT_INSTALLED: \"$name\" नाम का ऐप फ़ोन में नहीं मिला।\nINSTALLED: " +
                apps.joinToString(", ") { it.first }.take(1500)

        return launchMatch(ctx, match.first, match.second)
    }

    fun youtubeSearch(ctx: Context, q: String): String {
        return try {
            val i = Intent(Intent.ACTION_SEARCH)
                .setPackage("com.google.android.youtube")
                .putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            "youtube search opened for '$q'"
        } catch (e: ActivityNotFoundException) {
            openUrl(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(q))
        }
    }

    private val ytHttp by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(7, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /**
     * YouTube par sabse upar wala video (bina API key): results page se pehla "videoRenderer" videoId.
     * Network - IO thread par chalao. null = nahi mila.
     */
    fun youtubeFirstVideo(q: String): String? = try {
        val req = okhttp3.Request.Builder()
            .url("https://www.youtube.com/results?search_query=" + Uri.encode(q) + "&hl=hi&gl=IN")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "hi-IN,hi;q=0.9,en;q=0.8")
            .header("Cookie", "CONSENT=YES+1")
            .build()
        ytHttp.newCall(req).execute().use { r ->
            val src = r.body?.source() ?: return@use null
            YtPage.firstVideoId(src)
        }
    } catch (e: Exception) { DKLog.d("YouTube video id nahi mila: ${e.message}"); null }


    /** Seedha video chalao (search page + click ki zarurat nahi = ~10-20 sec bachte hain) */
    fun youtubePlay(ctx: Context, q: String, videoId: String?): String {
        if (videoId == null) return youtubeSearch(ctx, q) + " - direct play nahi hua, ab pehle video result par click karo"
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
                .setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "PLAYING: YouTube par '$q' ka video chal gaya (kaam pura, done=true)"
        } catch (e: Exception) {
            openUrl(ctx, "https://www.youtube.com/watch?v=$videoId")
            "PLAYING: '$q' ka video browser/YouTube me khola (kaam pura, done=true)"
        }
    }

    fun webSearch(ctx: Context, q: String): String {
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "web search opened for '$q'"
        } catch (e: ActivityNotFoundException) {
            openUrl(ctx, "https://www.google.com/search?q=" + Uri.encode(q))
        }
    }

    fun openUrl(ctx: Context, url: String): String = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "opened $url"
    } catch (e: Exception) {
        "ERROR: url nahi khula: ${e.message}"
    }

    fun dial(ctx: Context, number: String): String = try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "dialer opened with $number"
    } catch (e: Exception) {
        "ERROR: dialer nahi khula"
    }
}
