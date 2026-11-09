package com.dk.assistant.studio

import com.dk.assistant.forge.LocalSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** नवंबर 2026: ऐप स्टूडियो - GitHub न हो तो फ़ोन पर ही ऐप बनाना + चलता preview (कोडिंग एजेंट + DK कंप्यूटर) */
class AppStudioTest {
    private val C = StudioCore
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private fun manifest() = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()

    @Test fun detectsBuildRequests() {
        assertNotNull(C.extract("DK, एक todo ऐप बनाओ"))
        assertNotNull(C.extract("ek expense tracker app banao"))
        assertNotNull(C.extract("Android नेटिव का notes ऐप बना दो"))
        assertNotNull(C.extract("मेरे cafe की वेबसाइट बनाओ"))
        assertNotNull(C.extract("make a calculator web app"))
        assertNotNull(C.extract("Flutter में login screen वाला app बनाओ"))
    }

    @Test fun ignoresQuestionsGithubAndOtherWork() {
        assertNull(C.extract("ऐप कैसे बनाते हैं"))
        assertNull(C.extract("क्या तुम app बना सकते हो?"))
        assertNull(C.extract("GitHub repo में login ऐप बनाओ"))
        assertNull(C.extract("WhatsApp पर मैसेज लिखो"))
        assertNull(C.extract("व्हाट्सऐप खोलो"))
        assertNull(C.extract("Nashik के स्कूलों की PDF रिपोर्ट बनाओ"))
        assertNull(C.extract("यह code ठीक करो ```kotlin\nfun a() {}\n``` ऐप बनाओ"))
        assertNull(C.extract("Play Store से app download करो"))
    }

    @Test fun kindFromText() {
        assertEquals(StudioCore.Kind.ANDROID, C.kindOf("Android नेटिव notes ऐप"))
        assertEquals(StudioCore.Kind.ANDROID, C.kindOf("kotlin jetpack compose app"))
        assertEquals(StudioCore.Kind.FLUTTER, C.kindOf("flutter app"))
        assertEquals(StudioCore.Kind.REACT_NATIVE, C.kindOf("react native app"))
        assertEquals(StudioCore.Kind.IOS, C.kindOf("iphone app"))
        assertEquals(StudioCore.Kind.GAME, C.kindOf("सांप वाला गेम"))
        assertEquals(StudioCore.Kind.PWA, C.kindOf("offline चलने वाला ऐप"))
        assertEquals(StudioCore.Kind.WEB, C.kindOf("cafe की वेबसाइट"))
        assertEquals("preview/index.html", C.entryFor(StudioCore.Kind.ANDROID))
        assertEquals("index.html", C.entryFor(StudioCore.Kind.WEB))
    }

    @Test fun safePathsOnly() {
        assertEquals("index.html", C.safePath("./index.html"))
        assertEquals("android/app/src/main/java/com/x/MainActivity.kt", C.safePath("/android/app/src/main/java/com/x/MainActivity.kt"))
        assertNull(C.safePath("../../etc/passwd.txt"))
        assertNull(C.safePath("a/../../b.js"))
        assertNull(C.safePath(".studio"))
        assertNull(C.safePath("css/.hidden.css"))
        assertNull(C.safePath("noext"))
        assertEquals("android/gradlew", C.safePath("android/gradlew"))
        assertTrue(C.slug("एक todo ऐप बनाओ", 1_700_000_123_000L).matches(Regex("[a-z0-9-]+")))
        assertTrue(C.slug("एक todo ऐप बनाओ", 1_700_000_123_000L).startsWith("todo-app"))
    }

    @Test fun parsesFilesDeletesAndSummary() {
        val r = C.parse("""
            Here you go.
            ```html index.html
            <!doctype html><h1>Hi</h1>
            <script src="./app.js"></script>
            ```
            File: app.js
            ```js
            console.log(1)
            ```
            ```kotlin android/app/src/main/java/com/x/MainActivity.kt
            class MainActivity
            ```
            DELETE: old.css
            SUMMARY: एक todo ऐप बना — काम जोड़ें, हटाएँ।
        """.trimIndent())
        assertEquals(listOf("index.html", "app.js", "android/app/src/main/java/com/x/MainActivity.kt"), r.files.keys.toList())
        assertEquals("<!doctype html><h1>Hi</h1>\n<script src=\"./app.js\"></script>", r.files["index.html"])
        assertEquals("console.log(1)", r.files["app.js"])
        assertEquals(listOf("old.css"), r.deleted)
        assertTrue(r.summary.startsWith("एक todo ऐप बना"))
        // बिना रास्ते वाला घेरा = फ़ाइल नहीं (अंदाज़े का नाम नहीं)
        assertTrue(C.parse("```html\n<p>x</p>\n```").files.isEmpty())
        assertTrue(C.parse("```js ../../x.js\nbad\n```").files.isEmpty())
    }

    @Test fun promptsAskForRunnablePreviewAndNativeSource() {
        val web = C.system(StudioCore.Kind.WEB)
        assertTrue(web.contains("no CDN") && web.contains("SUMMARY:") && web.contains("COMPLETE file"))
        val nat = C.system(StudioCore.Kind.ANDROID)
        assertTrue(nat.contains("preview/") && nat.contains("android/") && nat.contains("Jetpack Compose"))
        assertTrue(C.buildTask("notes", StudioCore.Kind.ANDROID, 0).contains("preview/ only"))
        assertTrue(C.buildTask("notes", StudioCore.Kind.ANDROID, 1).contains("android/"))
        val fix = C.changeTask("", StudioCore.Kind.WEB, listOf("Uncaught TypeError: x is null (app.js:3)"))
        assertTrue(fix.contains("root cause") && fix.contains("app.js:3"))
        assertEquals("Uncaught ReferenceError: a is not defined (app.js:12)", C.errorLine("Uncaught ReferenceError: a is not defined", "https://h/studio/x/app.js?v=3", 12))
        val ctx = C.context(mapOf("index.html" to "<h1/>", "android/a.kt" to "x".repeat(5000)), 300)
        assertTrue(ctx.contains("index.html") && ctx.contains("Not shown for size") && ctx.contains("android/a.kt"))
    }

    @Test fun routeRules() {
        assertTrue(StudioRoute.noGithub("", "me/repo", false))
        assertTrue(StudioRoute.noGithub("tok", "", false))
        assertTrue(StudioRoute.noGithub("tok", "me/repo", true))
        assertFalse(StudioRoute.noGithub("tok", "me/repo", false))
        assertNotNull(StudioRoute.voiceApp("DK एक todo ऐप बनाओ"))
        assertNotNull(StudioRoute.voiceApp("calculator web app banao"))
        assertNull(StudioRoute.voiceApp("cafe की वेबसाइट बनाओ"))      // Forge वाला रास्ता पहले जैसा
        assertNull(StudioRoute.voiceApp("सांप वाला गेम बनाओ"))         // GameMaker पहले जैसा
    }

    @Test fun localSiteServesStudioFoldersSafely() {
        val root = Files.createTempDirectory("studio").toFile()
        File(root, "todo-app-1/css").mkdirs()
        File(root, "todo-app-1/index.html").writeText("<h1>x</h1>")
        File(root, "todo-app-1/css/style.css").writeText("h1{}")
        File(root, "secret.txt").writeText("no")
        assertEquals("style.css", LocalSite.resolveStudio("/studio/todo-app-1/css/style.css?v=2", root)?.name)
        assertEquals("index.html", LocalSite.resolveStudio("/studio/todo-app-1/", root)?.name)
        assertNull(LocalSite.resolveStudio("/studio/todo-app-1/../secret.txt", root))
        assertNull(LocalSite.resolveStudio("/studio/todo-app-1/%2e%2e/secret.txt", root))
        assertNull(LocalSite.resolveStudio("/studio/../secret.txt", root))
        assertEquals("application/manifest+json", LocalSite.contentType("manifest.webmanifest"))
        root.deleteRecursively()
    }

    @Test fun projectFilesStayInside() {
        val d = Files.createTempDirectory("proj").toFile()
        val p = StudioProject(File(d, "notes-1"))
        p.kind = StudioCore.Kind.ANDROID; p.title = "notes\nऐप"; p.saveMeta()
        assertEquals("preview/index.html", p.write("preview/index.html", "<p>hi</p>"))
        assertNull(p.write("../evil.txt", "x"))
        assertFalse(File(d, "evil.txt").exists())
        val again = StudioProject(File(d, "notes-1"))
        assertEquals(StudioCore.Kind.ANDROID, again.kind)
        assertEquals("notes\nऐप", again.title)
        assertEquals(listOf("preview/index.html"), again.files())
        assertTrue(again.hasPreview())
        val z = again.zip(File(d, "out.zip"))
        assertTrue(z.length() > 0)
        d.deleteRecursively()
    }

    @Test fun wiredIntoCoderComputerVoiceAndManifest() {
        val coder = src("CodingActivity.kt")
        assertTrue(coder.contains("studio.StudioRoute.noGithub(this)") && coder.contains("StudioSession.WHO_CODER"))
        // पुराने रास्ते वैसे ही
        assertTrue(coder.contains("OneKeyGate.check(this, OneKeyGate.Work.CODING)") && coder.contains("SevenRunner.start(this, t, media"))
        val pc = src("ComputerActivity.kt")
        assertTrue(pc.contains("StudioSession.WHO_COMPUTER") && pc.contains("ComputerSession.start(this, t)"))
        val ar = src("AgentRunner.kt")
        assertTrue(ar.indexOf("GameMaker.extract(goal)") < ar.indexOf("StudioRoute.voiceApp(goal)"))
        assertTrue(ar.indexOf("StudioRoute.voiceApp(goal)") < ar.indexOf("ForgeIntent.extract(goal)"))
        assertTrue(manifest().contains(".studio.StudioActivity"))
        val act = src("studio/StudioActivity.kt")
        assertTrue(act.contains("onConsoleMessage") && act.contains("StudioSession.fix(") && act.contains("LOAD_NO_CACHE"))
    }
}
