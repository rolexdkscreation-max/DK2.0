package com.dk.assistant.studio

/**
 * नवंबर 2026 (मालिक: "GitHub न जुड़ा हो तो कोडिंग एजेंट / DK कंप्यूटर आम कोडिंग एजेंट की तरह कोई भी ऐप - वेबसाइट, वेब ऐप,
 * Android नेटिव - बनाए, वहीं preview दे, बनते हुए दिखे, और वहीं से बदलाव करवा सकें").
 *
 * ऐप स्टूडियो के साफ़ नियम (Android नहीं -> JVM test):
 *  - बात "ऐप/वेबसाइट/टूल… बनाओ" वाली है? ([extract])
 *  - किस तरह का ऐप ([Kind]) - हर तरह का फ़ोन पर चलने वाला preview (वेब) ज़रूर; नेटिव / Flutter / iOS का असली code साथ में
 *  - AI का जवाब -> फ़ाइलें (```lang path``` घेरे) + "SUMMARY:" ([parse])
 *  - फ़ाइल का रास्ता सुरक्षित ([safePath]) - project फ़ोल्डर से बाहर कभी नहीं
 */
object StudioCore {

    /** ऐप का प्रकार: preview हमेशा वेब में (WebView), असली code प्रकार के हिसाब से */
    enum class Kind(val hi: String, val native: Boolean) {
        WEB("वेबसाइट / वेब ऐप", false),
        PWA("PWA (फ़ोन में install होने वाला वेब ऐप)", false),
        GAME("गेम (HTML5)", false),
        ANDROID("Android नेटिव ऐप (Kotlin + Jetpack Compose)", true),
        FLUTTER("Flutter ऐप", true),
        REACT_NATIVE("React Native ऐप", true),
        IOS("iOS ऐप (SwiftUI)", true),
    }

    /** हर नेटिव प्रकार का असली code इस फ़ोल्डर में; preview हमेशा [PREVIEW_DIR] में */
    const val PREVIEW_DIR = "preview"
    fun sourceDir(k: Kind): String = when (k) {
        Kind.ANDROID -> "android"; Kind.FLUTTER -> "flutter"; Kind.REACT_NATIVE -> "react-native"; Kind.IOS -> "ios"
        else -> ""
    }

    /** preview वाली पहली फ़ाइल (वेब = index.html, नेटिव = preview/index.html) */
    fun entryFor(k: Kind): String = if (k.native) "$PREVIEW_DIR/index.html" else "index.html"

    const val MAX_FILES = 80
    const val MAX_FILE_CHARS = 400_000

    // ------------------------------------------------------------------ पहचान
    private val SUBJECT = listOf(
        "app", "apps", "application", "एप", "ऐप", "एप्प", "ऐप्प", "एप्लीकेशन", "एप्लिकेशन", "ऐप्लिकेशन",
        "web app", "webapp", "वेब ऐप", "वेब एप", "pwa", "website", "web site", "webpage", "web page", "वेबसाइट", "वेब साइट", "वेब पेज",
        "landing page", "लैंडिंग पेज", "portfolio", "पोर्टफोलियो", "dashboard", "डैशबोर्ड", "tool", "टूल", "calculator", "कैलकुलेटर",
        "todo", "to-do", "टूडू", "game", "गेम", "खेल", "android", "एंड्रॉइड", "एंड्रॉयड", "flutter", "फ्लटर", "react native", "ios", "iphone",
        "tracker", "ट्रैकर",
    )
    private val VERB_WORDS = setOf("build", "create", "make", "design", "develop", "generate", "bana", "banao", "banado", "banaa",
        "banaiye", "banaye", "banayen", "banaen", "banana", "banake", "bnao", "bna", "taiyar", "tayyar")
    private val VERB_HINDI = listOf("बना", "बनाओ", "बनाइए", "बनाएं", "बनाएँ", "बनाना", "बनाकर", "बनाके", "बना दो", "तैयार कर", "डिज़ाइन कर", "डिजाइन कर")
    private val QUESTION_ANY = listOf("kaise bana", "kaise banate", "kaise banaye", "कैसे बना", "कैसे बनाते", "how to", "how do", "how can",
        "क्या हो सकता", "kya ho sakta", "is it possible", "बताओ कैसे")
    /** ये काम GitHub / रिपो / मौजूदा code के हैं - स्टूडियो नहीं */
    private val NOT_STUDIO = listOf("github", "गिटहब", "repo", "रिपो", "pull request", " pr ", "merge", "मर्ज", "branch", "ब्रांच", "commit",
        "release", "रिलीज़", "रिलीज", "play store", "प्ले स्टोर", "डाउनलोड", "download", "install कर", "इंस्टॉल कर", "```")

    /** "DK, एक todo ऐप बनाओ" -> विवरण; नहीं तो null */
    fun extract(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.length < 6) return null
        val low = " " + raw.lowercase().replace(Regex("\\s+"), " ") + " "
        val words = low.split(Regex("[^\\p{L}\\p{M}\\p{N}']+")).filter { it.isNotBlank() }.toSet()
        // एक शब्द = पूरा शब्द ही ("व्हाट्सऐप" में "ऐप" नहीं, "whatsapp" में "app" नहीं); कई शब्द = वाक्यांश
        val subject = SUBJECT.any { s -> if (s.contains(' ')) low.contains(" $s ") || low.contains(" $s") else s in words }
        if (!subject) return null
        val verb = words.any { it in VERB_WORDS } || VERB_HINDI.any { low.contains(it) }
        if (!verb) return null
        if (NOT_STUDIO.any { low.contains(it) }) return null
        if (raw.trimEnd().endsWith("?") || QUESTION_ANY.any { low.contains(it) }) return null
        return raw.trimEnd('.', '।', '!', ' ').ifBlank { null }
    }

    /** बात से ऐप का प्रकार */
    fun kindOf(text: String): Kind {
        val l = " " + text.lowercase() + " "
        fun has(vararg w: String) = w.any { l.contains(it) }
        return when {
            has("flutter", "फ्लटर", "dart") -> Kind.FLUTTER
            has("react native", "react-native", "expo") -> Kind.REACT_NATIVE
            has(" ios", "iphone", "आईफोन", "swiftui", " swift") -> Kind.IOS
            has("android", "एंड्रॉइड", "एंड्रॉयड", "kotlin", "कोटलिन", "jetpack", "compose", " apk", "नेटिव", "native") -> Kind.ANDROID
            has("game", "गेम", "खेल") -> Kind.GAME
            has("pwa", "install", "इंस्टॉल", "offline", "ऑफ़लाइन", "ऑफलाइन", "home screen", "होम स्क्रीन") -> Kind.PWA
            else -> Kind.WEB
        }
    }

    /** project का फ़ोल्डर नाम: "todo ऐप बनाओ" -> "todo-app-1234" (सिर्फ़ a-z0-9-) */
    fun slug(text: String, now: Long): String {
        val map = mapOf("ऐप" to "app", "एप" to "app", "गेम" to "game", "वेबसाइट" to "site", "कैलकुलेटर" to "calculator", "टूडू" to "todo",
            "डैशबोर्ड" to "dashboard", "पोर्टफोलियो" to "portfolio", "ट्रैकर" to "tracker", "खेल" to "game", "फ़ॉर्म" to "form", "फॉर्म" to "form")
        var t = text.lowercase()
        map.forEach { (h, e) -> t = t.replace(h, " $e ") }
        val stop = setOf("ek", "dk", "mera", "mere", "meri", "banao", "bana", "banado", "make", "create", "build", "the", "for", "and", "with",
            "wala", "wali", "please", "plz", "do", "de", "ka", "ki", "ke", "me", "mein", "an", "of", "to")
        val w = t.replace(Regex("[^a-z0-9]+"), " ").split(' ').filter { it.length >= 2 && it !in stop }.take(3).joinToString("-")
        return (w.ifBlank { "app" }).take(32) + "-" + (now / 1000 % 100000)
    }

    /** AI का दिया रास्ता -> project के अंदर का साफ़ रास्ता; बाहर जाने वाला / छिपा / अजीब = null */
    fun safePath(p: String): String? {
        var s = p.trim().replace('\\', '/').trim('`', '"', '\'', ' ')
        while (s.startsWith("./")) s = s.removePrefix("./")
        s = s.trimStart('/')
        if (s.isEmpty() || s.length > 160) return null
        val parts = s.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") && it != ".gitignore" }) return null
        if (parts.any { seg -> !seg.all { it.isLetterOrDigit() || it in "._-@+" } }) return null
        if (!parts.last().contains('.') && parts.last() !in setOf("gradlew", "Podfile", "Gemfile", "Makefile", "LICENSE")) return null
        return parts.joinToString("/")
    }

    data class Reply(val files: Map<String, String>, val deleted: List<String>, val summary: String)

    private val LANG_ONLY = setOf("html", "css", "js", "javascript", "json", "kotlin", "kt", "xml", "gradle", "kts", "dart", "swift", "ts", "tsx",
        "jsx", "typescript", "yaml", "yml", "md", "markdown", "text", "txt", "properties", "toml", "sh", "bash", "groovy", "plist", "svg")

    /**
     * AI का जवाब: ```lang path\n…\n``` घेरे (या घेरे के ठीक ऊपर "File: path") + DELETE: path + SUMMARY: …
     * एक ही रास्ता दो बार = आख़िरी वाला.
     */
    fun parse(reply: String): Reply {
        val files = linkedMapOf<String, String>()
        val deleted = mutableListOf<String>()
        val outside = StringBuilder()
        val lines = reply.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("```")) {
                val info = l.removePrefix("```").trim()
                val body = StringBuilder(); var j = i + 1
                while (j < lines.size && lines[j].trim() != "```") { if (j > i + 1) body.append('\n'); body.append(lines[j]); j++ }
                val prev = (i - 1 downTo maxOf(0, i - 2)).map { lines[it].trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                val name = pathIn(info) ?: pathIn(prev)
                if (name != null && body.length <= MAX_FILE_CHARS && files.size < MAX_FILES) files[name] = body.toString()
                i = j + 1; continue
            }
            Regex("^(?:DELETE|हटाओ)\\s*:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(l)?.let { m -> safePath(m.groupValues[1])?.let { deleted.add(it) } }
            outside.append(lines[i]).append('\n')
            i++
        }
        val o = outside.toString()
        val sm = Regex("(?is)SUMMARY\\s*:\\s*(.+)$").find(o)?.groupValues?.get(1)?.trim()
            ?: o.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("File", true) && !it.startsWith("DELETE", true) }.joinToString("\n")
        return Reply(files, deleted.filter { it !in files }, sm.take(2000))
    }

    /** "```kotlin android/app/src/main/java/x/MainActivity.kt" / "File: index.html" / "📄 app.js" -> रास्ता */
    private fun pathIn(s: String): String? {
        val toks = s.replace("📄", " ").replace(Regex("(?i)^(file|path|फ़ाइल|फाइल)\\s*[:：-]?"), " ").split(' ', '\t', ':', '：')
            .map { it.trim('`', '*', '"', '\'', ',', '(', ')') }.filter { it.isNotBlank() }
        return toks.firstOrNull { t -> t.lowercase() !in LANG_ONLY && (t.contains('.') || t.contains('/')) && safePath(t) != null }?.let { safePath(it) }
    }

    // ------------------------------------------------------------------ AI को काम
    /** हर काम पर एक जैसे नियम (आउटपुट का ढाँचा) */
    fun system(k: Kind): String = buildString {
        append("You are DK App Studio, a senior full-stack + mobile engineer working INSIDE the user's phone (no GitHub, no terminal, no build tools). ")
        append("You create complete, polished, working projects. The phone shows a LIVE PREVIEW of the project in a WebView, so the preview must run directly from files with no build step.\n\n")
        append("OUTPUT FORMAT (strict):\n")
        append("- Every file as a fenced block whose info line is the language and the relative path, e.g. ```html index.html  or ```kotlin android/app/src/main/java/com/example/app/MainActivity.kt\n")
        append("- Always the COMPLETE file content - never \"...\", never \"rest unchanged\", never placeholders or TODO stubs.\n")
        append("- To remove a file write a line: DELETE: path\n")
        append("- Never put ``` fences inside a file (README examples: indent code by 4 spaces instead).\n")
        append("- After all files, one line starting with SUMMARY: then, in simple Hindi (Devanagari), what was built / changed, the main features as a short list, and how to use it.\n\n")
        append("PREVIEW RULES (the phone preview):\n")
        append("- Plain HTML + CSS + vanilla JavaScript (ES2020), relative paths only (e.g. ./app.js, ./style.css), no CDN, no npm, no frameworks that need a build, no internet needed. Draw icons with inline SVG/emoji.\n")
        append("- Mobile first: <meta name=viewport content=\"width=device-width,initial-scale=1\">, touch friendly (44px targets), looks great on a phone, works on desktop too. Modern clean design, good contrast, smooth transitions.\n")
        append("- Data that should stay: localStorage. No alert()/prompt() popups - use in-page UI. No console errors.\n")
        append("- Keep each file focused; for bigger apps split into a few files (index.html, style.css, app.js, …).\n\n")
        when (k) {
            Kind.WEB -> append("PROJECT TYPE: website / web app. Entry: index.html at the project root.\n")
            Kind.GAME -> append("PROJECT TYPE: HTML5 game. Entry: index.html at the root. Canvas + requestAnimationFrame, touch controls + keyboard, start screen, score, rising difficulty, game over + restart, best score in localStorage, WebAudio sound effects (no files).\n")
            Kind.PWA -> append("PROJECT TYPE: installable PWA. Entry: index.html at the root, plus manifest.webmanifest (name, short_name, start_url \"./\", display standalone, theme/background colour, an inline SVG data-URI icon) and sw.js (offline cache of all files; register it with navigator.serviceWorker when available, ignore errors). Works fully offline.\n")
            else -> {
                append("PROJECT TYPE: ${k.hi}. It cannot run inside the phone, so you write TWO things:\n")
                append("1) A faithful clickable preview of the app's screens in ${PREVIEW_DIR}/ (entry ${PREVIEW_DIR}/index.html, HTML/CSS/JS as above) that looks and behaves like the real native app (same screens, navigation, colours, data flow).\n")
                append("2) The real, complete source in ${sourceDir(k)}/ that builds with the standard tools. ")
                append(when (k) {
                    Kind.ANDROID -> "Android Studio project: settings.gradle.kts, build.gradle.kts, gradle.properties, app/build.gradle.kts (compileSdk 34, minSdk 24, Kotlin + Jetpack Compose Material3), app/src/main/AndroidManifest.xml, MainActivity.kt and screen files under app/src/main/java/<package>/, res/values/strings.xml + themes.xml. Plus ${sourceDir(k)}/README.md (Hindi) with how to build the APK (Android Studio, or GitHub Actions once GitHub is connected).\n"
                    Kind.FLUTTER -> "Flutter project: pubspec.yaml, lib/main.dart (+ more lib/ files), README.md (Hindi) with flutter run / build apk steps.\n"
                    Kind.REACT_NATIVE -> "Expo React Native project: package.json, app.json, App.js (+ components/), README.md (Hindi) with npx expo start steps.\n"
                    else -> "SwiftUI Xcode-ready sources: App.swift, ContentView.swift (+ more views), README.md (Hindi) with Xcode steps.\n"
                })
                append("Keep the preview and the source in sync on every change.\n")
            }
        }
    }

    /** नया project: पहला काम */
    fun buildTask(request: String, k: Kind, stage: Int = 0): String = when {
        !k.native || stage == 0 ->
            "Build this ${if (k.native) "app's clickable preview (files in ${PREVIEW_DIR}/ only - the native source comes in the next step)" else "project"} exactly as the user asked:\n\"${request.trim()}\"\n" +
                "Make it complete and genuinely useful (real features, sample data where it helps). Entry file: ${entryFor(k)}."
        else ->
            "Now write the complete real ${k.hi} source in ${sourceDir(k)}/ for the same app (screens, navigation, colours and features exactly as in the preview above). " +
                "Do NOT repeat the preview files."
    }

    /** मौजूदा project में बदलाव / गलती ठीक करना */
    fun changeTask(change: String, k: Kind, errors: List<String>): String = buildString {
        if (change.isNotBlank()) append("The user wants this change in the current project:\n\"${change.trim()}\"\n")
        if (errors.isNotEmpty()) {
            append("The live preview reported these errors - find the root cause and fix them:\n")
            errors.take(12).forEach { append("- ").append(it.take(300)).append('\n') }
        }
        append("Return ONLY the files you change or add (complete content), plus DELETE lines if needed, then SUMMARY. Keep everything else working.")
        if (k.native) append(" Update both the preview (${PREVIEW_DIR}/) and the real source (${sourceDir(k)}/) so they stay in sync.")
    }

    /** project की फ़ाइलें AI को (बजट के अंदर; बड़ी फ़ाइलें पहले preview वाली) */
    fun context(files: Map<String, String>, budget: Int): String {
        val sb = StringBuilder("CURRENT PROJECT FILES:\n")
        val order = files.keys.sortedWith(compareBy({ if (it.startsWith("$PREVIEW_DIR/") || !it.contains('/')) 0 else 1 }, { it }))
        val left = mutableListOf<String>()
        for (p in order) {
            val body = files[p].orEmpty()
            val add = "\n```${p.substringAfterLast('.', "txt")} $p\n$body\n```\n"
            if (sb.length + add.length > budget) left.add(p) else sb.append(add)
        }
        if (left.isNotEmpty()) sb.append("\n(Not shown for size, exist unchanged: ${left.joinToString()})\n")
        return sb.toString()
    }

    /** preview में आई गलती को छोटा, एक जैसा रूप ("Uncaught TypeError: x is null (app.js:12)") */
    fun errorLine(msg: String, source: String?, line: Int): String {
        val file = source?.substringAfterLast('/')?.substringBefore('?').orEmpty()
        return msg.trim().take(240) + if (file.isNotBlank()) " ($file:$line)" else ""
    }

    /** बोलने / बुलबुले के लिए छोटा नतीजा */
    fun doneText(name: String, k: Kind, files: Int, summary: String): String =
        "✅ \"$name\" तैयार — ${k.hi}, $files फ़ाइलें। ऐप स्टूडियो में preview खुला है; वहीं नीचे लिखकर / बोलकर बदलाव करवाइए।" +
            (if (k.native) "\n📦 असली ${k.hi} का code \"${sourceDir(k)}/\" फ़ोल्डर में है (ZIP से ले सकते हैं)।" else "") +
            if (summary.isNotBlank()) "\n\n" + summary.take(900) else ""
}
