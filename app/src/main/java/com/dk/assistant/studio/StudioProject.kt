package com.dk.assistant.studio

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ऐप स्टूडियो का एक project = फ़ोन में एक फ़ोल्डर (filesDir/studio/<slug>/). फ़ाइलें यहीं सेव होती हैं (सिर्फ़ चैट में नहीं),
 * preview भी यहीं से चलता है. `.studio` = project की जानकारी (नाम, प्रकार, किसने बनाया, बातें).
 */
class StudioProject(val dir: File) {
    val slug: String get() = dir.name
    private val metaFile get() = File(dir, ".studio")

    var title: String = slug
    var kind: StudioCore.Kind = StudioCore.Kind.WEB
    /** किसने बनाया: "coder" = कोडिंग एजेंट, "computer" = DK कंप्यूटर */
    var who: String = "computer"
    var request: String = ""

    init { loadMeta() }

    private fun loadMeta() {
        val f = metaFile
        if (!f.isFile) return
        f.readLines().forEach { l ->
            val k = l.substringBefore('='); val v = l.substringAfter('=', "").replace("\\n", "\n")
            when (k) {
                "title" -> title = v
                "kind" -> kind = StudioCore.Kind.entries.firstOrNull { it.name == v } ?: StudioCore.Kind.WEB
                "who" -> who = v
                "request" -> request = v
            }
        }
    }

    fun saveMeta() {
        dir.mkdirs()
        metaFile.writeText(listOf("title" to title, "kind" to kind.name, "who" to who, "request" to request)
            .joinToString("\n") { (k, v) -> "$k=" + v.replace("\n", "\\n") })
    }

    fun entry(): String = StudioCore.entryFor(kind).takeIf { File(dir, it).isFile }
        ?: listOf("index.html", "${StudioCore.PREVIEW_DIR}/index.html").firstOrNull { File(dir, it).isFile }
        ?: files().firstOrNull { it.endsWith(".html") }
        ?: StudioCore.entryFor(kind)

    fun hasPreview(): Boolean = File(dir, entry()).isFile

    /** सारी फ़ाइलें (रास्ते), .studio के बिना */
    fun files(): List<String> = dir.walkTopDown().filter { it.isFile && it.name != ".studio" }
        .map { it.relativeTo(dir).path.replace('\\', '/') }.sorted().toList()

    fun read(path: String): String? = StudioCore.safePath(path)?.let { File(dir, it) }?.takeIf { it.isFile && inside(it) }?.readText()

    fun all(): Map<String, String> = files().associateWith { p -> File(dir, p).let { if (it.length() > StudioCore.MAX_FILE_CHARS) "(बड़ी फ़ाइल)" else it.readText() } }

    /** लिखो (रास्ता सुरक्षित हो तभी). return = लिखा गया रास्ता या null */
    fun write(path: String, content: String): String? {
        val p = StudioCore.safePath(path) ?: return null
        val f = File(dir, p)
        if (!inside(f)) return null
        f.parentFile?.mkdirs()
        f.writeText(content)
        return p
    }

    fun delete(path: String): Boolean {
        val p = StudioCore.safePath(path) ?: return false
        val f = File(dir, p)
        return inside(f) && f.isFile && f.delete()
    }

    private fun inside(f: File) = f.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    /** पूरा project ZIP में (शेयर के लिए) */
    fun zip(out: File): File {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            files().forEach { p -> z.putNextEntry(ZipEntry("$slug/$p")); File(dir, p).inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        return out
    }

    companion object {
        fun root(c: Context): File = File(c.filesDir, "studio").apply { mkdirs() }
        fun open(c: Context, slug: String): StudioProject? =
            slug.takeIf { it.matches(Regex("[a-z0-9-]{1,48}")) }?.let { File(root(c), it) }?.takeIf { it.isDirectory }?.let { StudioProject(it) }
        fun create(c: Context, slug: String): StudioProject = StudioProject(File(root(c), slug).apply { mkdirs() })
        fun all(c: Context): List<StudioProject> =
            (root(c).listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedByDescending { d -> d.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L }
                .map { StudioProject(it) }
    }
}
