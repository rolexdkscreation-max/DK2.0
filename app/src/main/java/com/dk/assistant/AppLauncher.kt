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

    fun open(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val query = name.trim().lowercase()

        // Agar seedha package name diya hai (com.xyz.app)
        if (query.contains('.')) {
            pm.getLaunchIntentForPackage(query)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "opened $query"
            }
        }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        val candidates = mutableListOf(query)
        aliases.forEach { (_, words) -> if (words.any { query.contains(it) }) candidates.addAll(words) }

        val match = candidates.firstNotNullOfOrNull { c -> apps.firstOrNull { it.first.lowercase() == c } }
            ?: candidates.firstNotNullOfOrNull { c ->
                apps.firstOrNull { it.first.lowercase().contains(c) || (it.first.length > 2 && c.contains(it.first.lowercase())) }
            }
            ?: groupOf(query)?.let { g -> packages[g]?.firstOrNull { launchPkg(ctx, it) }?.let { return "opened $g" } }
            ?: return "ERROR: app '$name' nahi mila. Installed apps: " +
                apps.joinToString(", ") { it.first }.take(1500)

        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "ERROR: ${match.first} launch nahi ho sakta"
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return "opened ${match.first}"
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
