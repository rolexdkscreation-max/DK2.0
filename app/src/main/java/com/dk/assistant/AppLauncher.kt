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
        "chrome" to listOf("chrome", "browser")
    )

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
                apps.firstOrNull { it.first.lowercase().contains(c) || c.contains(it.first.lowercase()) }
            }
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
