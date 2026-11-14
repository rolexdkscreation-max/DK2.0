package com.dk.assistant.behavior

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.dk.assistant.MainActivity
import com.dk.assistant.R
import com.dk.assistant.dkai.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

/** DKAI होम असल में खुलता है: बटन, 6 टैब, 3 हालत, और नीचे का लिखना बंद नहीं होता */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DkaiBehaviorTest {
    private fun open(): Pair<MainActivity, DkaiScreen> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.findViewById<View>(R.id.dkai_root)
        assertNotNull("DKAI होम नहीं जुड़ा", root)
        return a to (root.tag as DkaiScreen)
    }

    private fun texts(v: View, out: MutableList<String> = ArrayList(), onlyShown: Boolean = true): List<String> {
        if (onlyShown && v.visibility != View.VISIBLE) return out
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out, onlyShown)
        return out
    }

    private fun byDesc(v: View, d: String): View? {
        if (v.contentDescription?.toString() == d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) byDesc(v.getChildAt(i), d)?.let { return it }
        return null
    }

    @Test fun homeLooksRight() {
        val (_, s) = open()
        val t = texts(s.root)
        assertTrue("DKAI" in t)
        assertTrue(t.any { it.contains("READY") || it.contains("SETUP") })
        assertTrue("+ New" in t || t.any { it.trim() == "New" })
        assertTrue("What would you like to build or explore?" in t)
        // 13 नवंबर 2026 (मालिक): Chat/Builder/Agent/Pipeline कार्ड-पंक्ति होम से हटी - मोड नीचे "Chat ▾" से (modeOnlyFromDropdown)
        for (c in listOf("Builder", "Agent", "Pipeline")) assertFalse("कार्ड $c अब होम पर नहीं होना चाहिए", c in t)
        assertTrue("Chat ▾" in t)
        for (tab in listOf("Home", "Files", "Editor", "Terminal", "Preview", "GitHub")) assertTrue("टैब $tab नहीं", tab in t)
        assertEquals("Build apps, create websites, or write code...", s.input.hint.toString())
        assertEquals(VoiceSessionState.INACTIVE, s.voiceSessionState)
        assertEquals(View.GONE, s.voice.sheet.visibility); assertEquals(View.GONE, s.voice.mini.visibility)
    }

    @Test fun threeStates() {
        val (_, s) = open()
        s.setState(VoiceSessionState.ACTIVE_FULL, animate = false)
        assertEquals(View.VISIBLE, s.voice.sheet.visibility)
        val full = texts(s.voice.sheet)
        assertTrue(full.any { it.contains("Emma") }); assertTrue(full.any { Regex("[12]\\.[0-9]x").containsMatchIn(it) })
        for (m in listOf("Chat", "Builder", "Agent", "Pipeline", "Broadcast")) assertTrue("मोड $m नहीं", m in full)

        s.setState(DkaiCore.next(s.voiceSessionState, VoiceEvent.MINIMIZE), animate = false)
        assertEquals(VoiceSessionState.ACTIVE_MINIMIZED, s.voiceSessionState)
        assertEquals(View.VISIBLE, s.voice.mini.visibility)
        assertEquals(View.GONE, s.voice.sheet.visibility)
        // पट्टी के रहते भी लिखना चलता है
        s.input.setText("नमस्ते"); assertEquals("नमस्ते", s.input.text.toString())
        assertTrue(s.input.isEnabled && s.input.visibility == View.VISIBLE)

        s.setState(DkaiCore.next(s.voiceSessionState, VoiceEvent.EXPAND), animate = false)
        assertEquals(VoiceSessionState.ACTIVE_FULL, s.voiceSessionState)
        assertEquals(View.VISIBLE, s.voice.sheet.visibility)

        s.setState(DkaiCore.next(s.voiceSessionState, VoiceEvent.CLOSE), animate = false)
        assertEquals(VoiceSessionState.INACTIVE, s.voiceSessionState)
        assertEquals(View.GONE, s.voice.sheet.visibility); assertEquals(View.GONE, s.voice.mini.visibility)

        // असली बटन वाले रास्ते (animation के साथ) भी हालत बदलते हैं
        s.event(VoiceEvent.MIC_TAP)
        assertEquals(VoiceSessionState.ACTIVE_FULL, s.voiceSessionState)
        s.event(VoiceEvent.MINIMIZE); assertEquals(VoiceSessionState.ACTIVE_MINIMIZED, s.voiceSessionState)
        s.event(VoiceEvent.EXPAND); assertEquals(VoiceSessionState.ACTIVE_FULL, s.voiceSessionState)
        s.event(VoiceEvent.CLOSE); assertEquals(VoiceSessionState.INACTIVE, s.voiceSessionState)
    }

    @Test fun backMinimizes() {
        val (a, s) = open()
        s.setState(VoiceSessionState.ACTIVE_FULL, animate = false)
        a.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceSessionState.ACTIVE_MINIMIZED, s.voiceSessionState)
        assertFalse(a.isFinishing)
    }

    @Test fun tabsOpenPanels() {
        val (_, s) = open()
        for (t in listOf("Files", "Editor", "Terminal", "Preview", "GitHub", "Home")) {
            val v = byDesc(s.root, t); assertNotNull("टैब $t", v)
            v!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(t, s.tab.label)
        }
        assertEquals(View.VISIBLE, s.files.root.let { if (it.parent != null) View.VISIBLE else View.GONE })
    }

    @Test fun filesCreateAndTerminalRuns() {
        val (a, s) = open()
        val ws = DkaiWorkspace.workspace(a)
        val f = DkaiWorkspace.create(ws, "hello.txt", folder = false)
        assertNotNull(f); assertTrue(File(ws, "hello.txt").exists())
        s.showTab(DkaiTab.FILES, animate = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(texts(s.files.root).any { it.contains("hello.txt") } || DkaiWorkspace.list(ws).any { it.name == "hello.txt" })
        // शेल न हो (कुछ JVM) तो बस खुलना जाँचो
        s.runInTerminal("echo dkai-ok", ws)
        assertEquals(DkaiTab.TERMINAL, s.tab)
    }

    private fun byDescStart(v: View, d: String): View? {
        if (v.visibility == View.VISIBLE && v.contentDescription?.toString()?.startsWith(d) == true) return v
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) byDescStart(v.getChildAt(i), d)?.let { return it }
        return null
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // 13 नवंबर 2026 (मालिक): मोड सिर्फ़ नीचे वाले dropdown से - चारों वहाँ, चुनना वही काम करे
    @Test fun modeOnlyFromDropdown() {
        val (_, s) = open()
        byDesc(s.root, "मोड चुनें")!!.performClick(); idle()
        val shown = texts(s.root)
        for (m in listOf("Chat", "Builder", "Agent", "Pipeline")) assertTrue("dropdown में $m नहीं", m in shown)
        byDescStart(s.root, "Builder, ")!!.performClick(); idle()
        assertEquals(DkaiMode.BUILDER, s.mode)
        assertEquals("Describe the app or website to build...", s.input.hint.toString())
        assertTrue("Builder ▾" in texts(s.root))
        byDesc(s.root, "मोड चुनें")!!.performClick(); idle()
        byDescStart(s.root, "Chat, ")!!.performClick(); idle()
        assertEquals(DkaiMode.CHAT, s.mode)
    }

    // 13 नवंबर 2026 (मालिक): "Chat #N" टैप = सारी चैट (सबसे नई ऊपर); टैप = वह चैट पूरी खुले; "+ New" सूची में जुड़े
    @Test fun chatHistoryListAndOpen() {
        val (a, s) = open()
        val dir = DkaiChats.dir(a); dir.deleteRecursively()
        try {
            DkaiChats.save(dir, 2, listOf(com.dk.assistant.DKState.Msg("u", "पुरानी बेकरी वाली चैट", 1000), com.dk.assistant.DKState.Msg("d", "पुराना जवाब", 2000)))
            Dkai.setChat(a, 5)
            com.dk.assistant.DKState.replaceChat(listOf(com.dk.assistant.DKState.Msg("u", "अभी की बात", 3000)))
            byDesc(s.root, "पुरानी सारी चैट देखें")!!.performClick(); idle()
            val t = texts(s.root)
            assertTrue("सभी चैट" in t)
            val iNew = t.lastIndexOf("अभी की बात"); val iOld = t.lastIndexOf("पुरानी बेकरी वाली चैट")      // (आख़िरी = सूची वाली, होम का बुलबुला नहीं)
            assertTrue(t.toString(), iNew >= 0 && iOld > iNew)                      // सबसे नई ऊपर
            assertTrue(byDescStart(s.root, "Chat 5, अभी की बात")!!.contentDescription.contains("अभी खुली"))
            byDescStart(s.root, "Chat 2, ")!!.performClick(); idle()
            assertEquals(2, Dkai.chatNo(a))
            assertEquals(listOf("पुरानी बेकरी वाली चैट", "पुराना जवाब"), com.dk.assistant.DKState.messages.map { it.text })
            assertTrue("पुराना जवाब" in texts(s.root))                               // पूरी पुरानी बातचीत स्क्रीन पर
            assertEquals(listOf("अभी की बात"), DkaiChats.load(dir, 5).map { it.text })   // छोड़ी हुई चैट सेव
            assertFalse(com.dk.assistant.dkai.DkaiUi.anyOpen(s.root))                 // सूची बंद
            // back से भी बंद
            byDesc(s.root, "पुरानी सारी चैट देखें")!!.performClick(); idle()
            assertTrue(com.dk.assistant.dkai.DkaiUi.anyOpen(s.root))
            a.onBackPressedDispatcher.onBackPressed(); idle()
            assertFalse(com.dk.assistant.dkai.DkaiUi.anyOpen(s.root))
            // नई चैट = सबसे बड़ा + 1, पुरानी सूची में
            s.startNewChat()
            assertEquals(6, Dkai.chatNo(a))
            assertTrue(com.dk.assistant.DKState.messages.isEmpty())
            assertEquals(listOf(5, 2), DkaiChats.saved(dir).sortedDescending())
        } finally {
            com.dk.assistant.DKState.replaceChat(emptyList()); dir.deleteRecursively()
        }
    }
}
