package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "PR चेक बार-बार फेल क्यों?"): branch feature/dk-1110-2225 पर कोडिंग एजेंट ने
 * default wallpaper dk_theme_bg.webp (88 KB की तस्वीर) को अपने text वाले औज़ार से "दोबारा लिख" दिया -
 * पहले 27 byte, फिर 789 byte की टूटी फ़ाइल (binary bytes text/UTF-8 बन गए). test newDefaultWallpaperNotOldBlue
 * हर बार fail हुआ, और सुधार के नाम पर 3 खाली commit (कोई बदलाव नहीं) + test फ़ाइल का एक newline बदला गया -
 * इसलिए 6 बार लगातार वही fail.
 *
 * यह pure जाँच (Android import नहीं -> JVM test):
 *  1. [isBinary] / [blockedWrites] - तस्वीर/font/आवाज़/jar वगैरह text औज़ार से कभी नहीं लिखी जातीं (हटाना अलग जाँच से)
 *  2. [culprits] - test fail हुआ और उसी test (या log) में इसी काम की बदली binary फ़ाइल का नाम है = जड़ वही:
 *     फ़ाइल main से हूबहू वापस (blob sha से, bytes नहीं बिगड़ते)
 *  3. [NO_CHANGE] - सुधार से कोई असली बदलाव नहीं बना (खाली commit) = वही build दोबारा नहीं चलाना,
 *     डिबगर को साफ़ बताना कि पिछला सुधार कुछ नहीं बदला
 */
object BinaryGuard {
    private val EXT = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "heic", "avif",
        "ttf", "otf", "woff", "woff2",
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "mp4", "webm",
        "jar", "aar", "zip", "apk", "aab", "so", "dex", "class", "bin", "onnx", "tflite", "pt",
        "keystore", "jks", "pdf"
    )

    fun isBinary(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in EXT

    /** इन path पर text content लिखना मना (null = हटाना, वह deleteBlock से अलग जाँचा जाता है) */
    fun blockedWrites(changes: Map<String, String?>): Set<String> =
        changes.filter { (p, c) -> c != null && isBinary(p) }.keys

    /** commit में सिर्फ़ text वाले बदलाव (binary लिखना हटाया) */
    fun safeChanges(changes: Map<String, String?>): Map<String, String?> =
        changes.filterNot { (p, c) -> c != null && isBinary(p) }

    /** कोडर/डिबगर के लिए साफ़ बात */
    fun refusal(path: String): String =
        "$path binary फ़ाइल (तस्वीर/font/आवाज़) है — text औज़ार से लिखने पर टूट जाती है, इसलिए बदलाव रोका. " +
            "ज़रूरत हो तो सिर्फ़ code बदलें (जैसे दूसरी फ़ाइल का नाम), तस्वीर खुद यूज़र डाले"

    private val TEST_FAIL = Regex("""^([\w.$]+)\s+>\s+(.+?)\s+FAILED$""")

    /** log में fail हुए test class (पूरा नाम, जैसे com.dk.assistant.CoderHeaderTaskTest) */
    fun failedTestClasses(log: String): List<String> =
        log.lines().mapNotNull { TEST_FAIL.find(BuildLog.clean(it).trim())?.groupValues?.get(1) }
            .map { it.substringBefore('$') }.distinct()

    /** test class -> repo में उसकी फ़ाइल (src/test/... /Name.kt) */
    fun testFile(cls: String, paths: Collection<String>): String? {
        val tail = cls.replace('.', '/')
        return paths.firstOrNull { it.endsWith("$tail.kt") || it.endsWith("$tail.java") }
            ?: paths.firstOrNull { it.endsWith("/" + cls.substringAfterLast('.') + ".kt") }
    }

    /**
     * इसी काम की बदली/हटाई binary फ़ाइलें जिनका नाम log में या fail हुए test की फ़ाइल में है.
     * [changedBinary] = main से तुलना में बदली binary फ़ाइलें; [testSource] = test फ़ाइल का text (path -> content)
     */
    fun culprits(log: String, changedBinary: Collection<String>, testSource: (String) -> String?, paths: Collection<String>): List<String> {
        if (changedBinary.isEmpty()) return emptyList()
        val tests = failedTestClasses(log).mapNotNull { testFile(it, paths) }.mapNotNull { testSource(it) }
        return changedBinary.filter { p ->
            val name = p.substringAfterLast('/')
            val stem = name.substringBeforeLast('.')
            log.contains(name) || tests.any { t -> t.contains(name) || t.contains("R.drawable.$stem") || t.contains("\"$stem\"") }
        }.distinct()
    }

    /** खाली सुधार के बाद डिबगर को जाने वाला log का पहला हिस्सा */
    const val NO_CHANGE = "PREVIOUS FIX CHANGED NOTHING: the pushed files were byte-identical to the branch (empty commit), so the build was NOT re-run. " +
        "The failure below is still there. Do NOT retry the same thing - read the exact failing test/assert and change the real cause (or restore the damaged file from main)."
}
