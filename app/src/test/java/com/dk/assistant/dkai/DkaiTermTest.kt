package com.dk.assistant.dkai

import com.dk.assistant.Grounding
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 13 नवंबर 2026: एडवांस DKAI Terminal + DK लाइव "terminal" tool + नए orb + chat में स्रोत - असली नियम */
class DkaiTermTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun aliases() {
        val a = DkaiTerm.DEFAULT_ALIASES
        assertEquals("ls -la", DkaiTerm.expandAlias("ll", a))
        assertEquals("ls -la src", DkaiTerm.expandAlias("ll src", a))
        assertEquals("cd ..", DkaiTerm.expandAlias("..", a))
        assertEquals("echo hi", DkaiTerm.expandAlias("echo hi", a))
        // एक-दूसरे में फँसे alias = अटके नहीं
        val loop = mapOf("a" to "b x", "b" to "a y")
        assertNotNull(DkaiTerm.expandAlias("a", loop))
        assertEquals("ll" to "ls -la", DkaiTerm.aliasDef("ll='ls -la'"))
        assertEquals("g" to "grep -n", DkaiTerm.aliasDef("g=\"grep -n\""))
        assertNull(DkaiTerm.aliasDef("bad"))
        assertNull(DkaiTerm.aliasDef("x="))
    }

    @Test fun historyAndEnv() {
        val h = listOf("ls", "pwd", "echo 3")
        assertEquals("echo 3", DkaiTerm.expandHistory("!!", h))
        assertEquals("pwd", DkaiTerm.expandHistory("!2", h))
        assertNull(DkaiTerm.expandHistory("!9", h))
        assertNull(DkaiTerm.expandHistory("!!", emptyList()))
        assertEquals("ls -l", DkaiTerm.expandHistory("ls -l", h))
        assertEquals(mapOf("A" to "1", "B" to "x y", "C" to "z"), DkaiTerm.pairs("A=1 B='x y' C=\"z\""))
        assertTrue(DkaiTerm.pairs("nothing").isEmpty())
    }

    @Test fun builtins() {
        for (b in listOf("cd", "tree", "wget", "curl", "zip", "unzip", "sysinfo", "alias", "export", "history", "help"))
            assertTrue(b, DkaiTerm.isBuiltin("$b x"))
        assertFalse(DkaiTerm.isBuiltin("ls -la"))
        assertEquals("ls", DkaiTerm.head("  ls   -la"))
    }

    @Test fun riskyAsksFirst() {
        for (c in listOf("rm -rf test", "ls; rm a", "mv a b", "echo hi > a.txt", "sed -i s/a/b/ f", "find . -name x -delete", "unzip -o a.zip", "kill 12", "ls | xargs rm"))
            assertTrue(c, DkaiTerm.risky(c))
        for (c in listOf("ls -la", "echo hi >> log.txt", "ls 2>&1", "cat a | grep b", "mkdir test", "tree", "format x", "echo farm"))
            assertFalse(c, DkaiTerm.risky(c))
    }

    @Test fun clipAndAnsi() {
        val s = "a".repeat(100) + "b".repeat(100)
        val c = DkaiTerm.clip(s, 50)
        assertTrue(c.startsWith("a".repeat(20))); assertTrue(c.endsWith("b".repeat(30))); assertTrue(c.contains("150"))
        assertEquals("short", DkaiTerm.clip("short", 50))
        assertEquals("red\nx", DkaiTerm.stripAnsi("\u001B[31mred\u001B[0m\r\nx"))
    }

    @Test fun tabComplete() {
        val names = listOf("index.html", "images/", "notes.txt")
        assertEquals("cat notes.txt ", DkaiTerm.complete("cat no", names).first)
        assertEquals("cd images/", DkaiTerm.complete("cd ima", names).first)
        val many = DkaiTerm.complete("cat i", names)
        assertEquals("cat i", many.first)      // साझा शुरू "i" ही
        assertEquals(listOf("images/", "index.html"), many.second)
        assertEquals("cat src/notes.txt ", DkaiTerm.complete("cat src/no", names).first)
        assertEquals("cat zz", DkaiTerm.complete("cat zz", names).first)
    }

    @Test fun treeAndZipSafety() {
        val d = Files.createTempDirectory("dkterm").toFile()
        File(d, "src").mkdirs(); File(d, "src/a.kt").writeText("x"); File(d, "readme.md").writeText("y")
        File(d, "node_modules/pkg").mkdirs(); File(d, "node_modules/pkg/i.js").writeText("z")
        val t = DkaiTerm.tree(d)
        assertTrue(t, t.contains("├── node_modules/") || t.contains("└── node_modules/"))
        assertFalse(t.contains("i.js"))
        assertTrue(t.contains("a.kt")); assertTrue(t.contains("readme.md"))
        assertTrue(t.trimEnd().endsWith("2 फ़ोल्डर, 2 फ़ाइल"))
        assertNotNull(DkaiTerm.safeEntry(d, "src/b.txt"))
        assertNull(DkaiTerm.safeEntry(d, "../../etc/passwd"))
        d.deleteRecursively()
    }

    @Test fun fetchWords() {
        assertEquals(DkaiTerm.Fetch("https://x.com/a/logo.png", "logo.png", true), DkaiTerm.parseFetch("wget https://x.com/a/logo.png"))
        assertEquals(DkaiTerm.Fetch("https://x.com/a", "my.html", true), DkaiTerm.parseFetch("wget https://x.com/a -O my.html"))
        assertEquals(DkaiTerm.Fetch("https://api.x.com/v1?q=1", null, false), DkaiTerm.parseFetch("curl https://api.x.com/v1?q=1"))
        assertTrue(DkaiTerm.parseFetch("curl -o out.json https://api.x.com/v1")!!.save)
        assertNull(DkaiTerm.parseFetch("wget"))
        assertEquals("index.html", DkaiTerm.fileFromUrl("https://x.com/"))
        assertEquals("1.5 KB", DkaiTerm.size(1536))
    }

    @Test fun knowledgeForDkLive() {
        val k = DkaiTerm.KNOWLEDGE
        for (w in listOf("Workspace", "wget", "tree", "sysinfo", "history", "NOT available", "git")) assertTrue(w, k.contains(w))
        assertTrue(DkaiTerm.HELP.contains("DK लाइव"))
    }

    @Test fun liveToolWired() {
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("put(\"name\", \"terminal\")"))
        assertTrue(setup.contains("DkaiTerm.KNOWLEDGE"))
        assertTrue(setup.contains("\"confirmed\""))
        assertTrue(src("GeminiLiveTools.kt").contains("\"terminal\" -> com.dk.assistant.dkai.DkaiShell.liveTool(svc, args)"))
        assertTrue(src("SideTalk.kt").contains("\"terminal\" -> com.dk.assistant.dkai.DkaiTerm.risky"))
        assertTrue(src("LiveConnect.kt").contains("DKAI Terminal"))
        assertTrue(src("LivePromise.kt").contains("\"terminal\" ->"))
        val shell = src("dkai/DkaiShell.kt")
        assertTrue(shell.contains("CONFIRM_NEEDED")); assertTrue(shell.contains("listeners"))
        // Terminal स्क्रीन DK लाइव वाला सत्र सुनती है + चलती कमांड को जवाब
        val panel = src("dkai/DkaiPanels.kt")
        assertTrue(panel.contains("DkaiShell.listeners.add")); assertTrue(panel.contains("stdin"))
    }

    @Test fun orbs() {
        val keys = DkaiOrbs.ORBS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.size >= 8)
        for (k in listOf("aurora", "sunset", "reactor", "solar", "crystal", "swarm", "classic")) assertTrue(k, k in keys)
        assertEquals(DkaiOrbs.FLUID, DkaiOrbs.valid("बेकार"))
        assertEquals(DkaiOrbs.FLUID, DkaiOrbs.valid(null))
        assertEquals("reactor", DkaiOrbs.valid("reactor"))
        for (k in keys) assertEquals(3, DkaiOrbs.palette(k).size)
        assertEquals(0x80123456.toInt(), DkaiOrbs.a(0xFF123456.toInt(), 0x80))
        val views = src("dkai/DkaiViews.kt")
        for (f in listOf("fun reactor(", "fun solar(", "fun crystal(", "fun swarm(")) assertTrue(f, views.contains(f))
        assertTrue(src("dkai/DkaiVoice.kt").contains("DkaiOrbs.pick"))
        // 13 नवंबर 2026 (मालिक): orb बदलना ☰ मेनू से हटकर Settings → दिखावट में
        assertTrue(src("SettingsLook.kt").contains("card(\"DKAI का orb\""))
        assertTrue(src("SettingsLook.kt").contains("DkaiOrbs.ORBS"))
    }

    @Test fun chatShowsSources() {
        assertEquals("ndtv.com", Grounding.site("ndtv.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"))
        assertEquals("en.wikipedia.org", Grounding.site("Some page", "https://en.wikipedia.org/wiki/X"))
        assertEquals(listOf("a.com", "b.org"), Grounding.sites(listOf("x" to "https://a.com/1", "y" to "https://www.a.com/2", "z" to "https://b.org")))
        val screen = src("dkai/DkaiScreen.kt")
        assertTrue(screen.contains("chatList.addView(sources(m.src))"))
        assertTrue(screen.contains("Grounding.sites"))
        assertTrue(src("SourcesView.kt").contains("Grounding.site("))
    }
}
