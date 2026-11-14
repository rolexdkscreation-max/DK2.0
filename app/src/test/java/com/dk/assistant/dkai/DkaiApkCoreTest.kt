package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 14 नवंबर 2026 (मालिक): "Terminal से कैलकुलेटर ऐप बनाओ - नहीं बना". अब फ़ोन पर ही असली APK: यहाँ असली साँचे
 * (`:apkshell` का बना APK) से पूरा APK बनाकर Google के apksig verifier से जाँचते हैं - Android जिस तरह जाँचता है.
 */
class DkaiApkCoreTest {
    private fun template(): File {
        val f = listOf("build/generated/dkshell/dk_apkshell.apk", "../apkshell/build/outputs/apk/release/apkshell-release-unsigned.apk")
            .map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("APK साँचा (dk_apkshell.apk) build में नहीं बना - app/build.gradle.kts का dkShellApk देखें", f)
        return f!!
    }

    private fun tmp(): File = kotlin.io.path.createTempDirectory("dkapk").toFile()

    private fun calcApp(dir: File): File {
        dir.mkdirs()
        File(dir, "index.html").writeText("<!doctype html><meta charset=utf-8><link rel=stylesheet href=style.css><div id=d>0</div><script src=app.js></script>")
        File(dir, "style.css").writeText("body{font-family:sans-serif}")
        File(dir, "js").mkdirs(); File(dir, "js/x.js").writeText("1")
        File(dir, "app.js").writeText("document.getElementById('d').textContent = 2+2")
        File(dir, ".studio").writeText("title=कैलकुलेटर")          // DK की अपनी फ़ाइल - ऐप में नहीं जाती
        return dir
    }

    @Test fun templateHasPlaceholders() {
        val m = ZipFile(template()).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val s = DkaiApkCore.manifestStrings(m)
        assertTrue(DkaiApkCore.PLACEHOLDER_PKG in s)
        assertTrue(DkaiApkCore.PLACEHOLDER_LABEL in s)
        assertTrue("activity का class नाम package से अलग होना चाहिए (बदलने पर न टूटे)", "com.dk.apkshell.Main" in s)
    }

    @Test fun manifestPatchChangesOnlyPlaceholders() {
        val m = ZipFile(template()).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val before = DkaiApkCore.manifestStrings(m)
        val out = DkaiApkCore.manifestFor(m, "com.dkapp.calculator", "कैलकुलेटर ऐप")
        val after = DkaiApkCore.manifestStrings(out)
        assertEquals(before.size, after.size)
        for (i in before.indices) when (before[i]) {
            DkaiApkCore.PLACEHOLDER_PKG -> assertEquals("com.dkapp.calculator", after[i])
            DkaiApkCore.PLACEHOLDER_LABEL -> assertEquals("कैलकुलेटर ऐप", after[i])
            else -> assertEquals(before[i], after[i])
        }
        // फ़ाइल का size header में सही
        assertEquals(out.size, java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4))
        // वापस बदलो = हू-ब-हू पुराना
        val back = DkaiApkCore.patchManifest(out) { if (it == "com.dkapp.calculator") DkaiApkCore.PLACEHOLDER_PKG else if (it == "कैलकुलेटर ऐप") DkaiApkCore.PLACEHOLDER_LABEL else it }
        assertEquals(before, DkaiApkCore.manifestStrings(back))
        assertEquals(m.size, back.size)
    }

    @Test fun badPackageRefused() {
        val m = ZipFile(template()).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        assertThrows(IllegalArgumentException::class.java) { DkaiApkCore.manifestFor(m, "1bad", "x") }
    }

    @Test fun buildsSignedInstallableApk() {
        val t = tmp()
        val web = calcApp(File(t, "calc"))
        val keys = File(t, "keys")
        val b = DkaiApkCore.build(template(), web, "Calculator", File(t, "apks"), keys, File(t, "cache"))
        assertEquals("com.dkapp.calculator", b.pkg)
        assertEquals("calculator.apk", b.apk.name)
        assertEquals(4, b.files)
        val r = com.android.apksig.ApkVerifier.Builder(b.apk).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("sign जाँच फ़ेल: ${r.errors}", r.isVerified)
        assertTrue(r.isVerifiedUsingV2Scheme)
        System.getenv("DK_APK_DUMP")?.let { b.apk.copyTo(File(it), overwrite = true) }
        ZipFile(b.apk).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            for (n in listOf("assets/www/index.html", "assets/www/app.js", "assets/www/style.css", "assets/www/js/x.js", "classes.dex", "resources.arsc"))
                assertTrue("$n APK में नहीं", n in names)
            assertFalse(".studio ऐप में नहीं जानी चाहिए", names.any { it.endsWith(".studio") })
            assertEquals("resources.arsc बिना दबाए (Android 11+ की शर्त)", ZipEntry.STORED, z.getEntry("resources.arsc").method)
            val s = DkaiApkCore.manifestStrings(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
            assertTrue("com.dkapp.calculator" in s); assertTrue("Calculator" in s)
            assertFalse(DkaiApkCore.PLACEHOLDER_PKG in s); assertFalse(DkaiApkCore.PLACEHOLDER_LABEL in s)
            assertEquals("2+2", String(z.getInputStream(z.getEntry("assets/www/app.js")).readBytes()).substringAfter("= "))
        }
        // दोबारा बनाओ = वही key (पुराने ऐप के ऊपर अपडेट हो सके)
        val cert1 = r.signerCertificates.first().encoded
        File(web, "app.js").writeText("document.getElementById('d').textContent = 3+3")
        val b2 = DkaiApkCore.build(template(), web, "Calculator", File(t, "apks"), keys, File(t, "cache"))
        val r2 = com.android.apksig.ApkVerifier.Builder(b2.apk).build().verify()
        assertTrue(r2.isVerified)
        assertArrayEquals("दूसरी बार अलग key = अपडेट नहीं होगा", cert1, r2.signerCertificates.first().encoded)
        assertFalse(File(t, "apks/calculator.apk.part").exists())
        assertTrue("बिना sign वाली फ़ाइल पीछे नहीं छूटनी चाहिए", File(t, "cache").listFiles().orEmpty().none { it.name.startsWith("dk_unsigned") })
        t.deleteRecursively()
    }

    @Test fun storedEntriesAligned() {
        val t = tmp()
        val b = DkaiApkCore.build(template(), calcApp(File(t, "c")), "Calc", File(t, "apks"), File(t, "k"), File(t, "cache"))
        // ZIP का central directory पढ़कर: हर STORED फ़ाइल का data 4-byte पर (zipalign जैसा) - वरना Android 11+ पर इंस्टॉल फ़ेल
        val bytes = b.apk.readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var eocd = bytes.size - 22
        while (eocd > 0 && bb.getInt(eocd) != 0x06054b50) eocd--
        val count = bb.getShort(eocd + 10).toInt() and 0xffff
        var p = bb.getInt(eocd + 16)
        var stored = 0
        repeat(count) {
            assertEquals(0x02014b50, bb.getInt(p))
            val method = bb.getShort(p + 10).toInt()
            val nl = bb.getShort(p + 28).toInt() and 0xffff; val xl = bb.getShort(p + 30).toInt() and 0xffff; val cl = bb.getShort(p + 32).toInt() and 0xffff
            val name = String(bytes, p + 46, nl)
            val lho = bb.getInt(p + 42)
            val data = lho + 30 + (bb.getShort(lho + 26).toInt() and 0xffff) + (bb.getShort(lho + 28).toInt() and 0xffff)
            if (method == 0) { stored++; assertEquals("$name 4-byte पर नहीं", 0, data % 4) }
            p += 46 + nl + xl + cl
        }
        assertTrue("resources.arsc STORED होना चाहिए", stored >= 1)
        t.deleteRecursively()
    }

    @Test fun webRootFindsStudioPreview() {
        val t = tmp()
        assertNull(DkaiApkCore.webRoot(t))
        File(t, "preview").mkdirs(); File(t, "preview/index.html").writeText("x")
        assertEquals(File(t, "preview"), DkaiApkCore.webRoot(t))
        File(t, "index.html").writeText("y")
        assertEquals(t, DkaiApkCore.webRoot(t))
        t.deleteRecursively()
    }

    @Test fun names() {
        assertEquals("com.dkapp.calculator", DkaiApkCore.packageFor("Calculator"))
        assertEquals("com.dkapp.todolist", DkaiApkCore.packageFor("Todo List!"))
        assertEquals("com.dkapp.a2048game", DkaiApkCore.packageFor("2048 Game"))
        val hi = DkaiApkCore.packageFor("कैलकुलेटर")
        assertTrue(hi, Regex("^com\\.dkapp\\.app[0-9a-f]+$").matches(hi))
        assertEquals("एक ही नाम = एक ही package (अपडेट)", hi, DkaiApkCore.packageFor(" कैलकुलेटर "))
        assertEquals("DK App", DkaiApkCore.labelFor("  "))
        assertEquals("कैलकुलेटर ऐप", DkaiApkCore.appName("एक कैलकुलेटर ऐप बनाओ"))
        assertEquals("कैलकुलेटर ऐप", DkaiApkCore.appName("टर्मिनल से कैलकुलेटर ऐप बना दो"))
        assertEquals("Calculator app", DkaiApkCore.appName("make a calculator app"))
        assertTrue(DkaiApkCore.appName("बनाओ").isNotBlank())
    }
}
