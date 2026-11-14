package com.dk.assistant.dkai

import android.content.Context
import com.dk.assistant.*
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** zip खोलना (Import Zip Project / GitHub Clone) - zip-slip से सुरक्षित */
object DkaiZip {
    /** [zip] -> [dest]; सबकी एक ऊपर वाली परत हो तो हटाओ. वापस: कितनी फ़ाइलें */
    fun unzip(zip: File, dest: File): Int {
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            val tops = names.mapNotNull { it.replace('\\', '/').trimStart('/').substringBefore('/', "").takeIf { t -> t.isNotEmpty() } }.toSet()
            val strip = tops.size == 1 && names.all { it.replace('\\', '/').trimStart('/').contains('/') || it.endsWith("/") }
            var n = 0
            dest.mkdirs()
            for (e in z.entries()) {
                val rel = DkaiKit.zipTarget(e.name, strip) ?: continue
                val f = File(dest, rel)
                if (!DkaiCore.inside(dest, f)) continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                n++
            }
            return n
        }
    }

    private val http by lazy { NetFix.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).followRedirects(true).build() }

    /** GitHub रिपो -> DKAIProjects/[folder] (token हो तो private भी) */
    fun clone(c: Context, owner: String, repo: String, folder: String): Pair<File, Int> {
        val dest = File(DkaiWorkspace.projectsRoot(c), folder)
        if (dest.exists() && (dest.list()?.isNotEmpty() == true)) throw IllegalStateException("\"$folder\" पहले से मौजूद है - दूसरा नाम दें")
        val tok = Prefs.ghToken(c)
        val url = if (tok.isNotBlank()) "https://api.github.com/repos/$owner/$repo/zipball" else "https://codeload.github.com/$owner/$repo/zip/HEAD"
        val b = Request.Builder().url(url).header("User-Agent", "DKAI")
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok").header("Accept", "application/vnd.github+json")
        val tmp = File(c.cacheDir, "clone_${System.currentTimeMillis()}.zip")
        try {
            http.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException(if (r.code == 404) "रिपो नहीं मिला (नाम देखें / private हो तो GitHub token)" else "GitHub ${r.code}")
                r.body?.byteStream()?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: throw IllegalStateException("खाली जवाब")
            }
            val n = unzip(tmp, dest)
            try {
                File(dest, DkaiKit.CONFIG_DIR).mkdirs()
                File(dest, DkaiKit.CONFIG_DIR + "/config.json").writeText(org.json.JSONObject().put("name", folder).put("repo", "$owner/$repo")
                    .put("brand", DkaiKit.BRAND).put("port", DkaiKit.PORT).put("cloned", System.currentTimeMillis()).toString(2))
            } catch (_: Exception) {}
            DkaiWorkspace.setProject(c, dest)
            return dest to n
        } finally { tmp.delete() }
    }
}
