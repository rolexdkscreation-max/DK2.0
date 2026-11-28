package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.backend.BackendWire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक): "All with one key (all-rounder)" चालू = DK चैट सिर्फ़ DK सर्वर की चैट वाली fallback कड़ी से
 * (body.chat_chain = true), ऐप की Gemini key से बिल्कुल नहीं. बंद = पुराना रास्ता जैसा का तैसा. DK Live / कोडिंग पर असर नहीं.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatServerOnlyNov28Test {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() { AiRouter.chatServerOverride = null; AiRouter.readyOverride = null }

    private fun src(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant"), File(d, "app/src/main/java/com/dk/assistant"))
                .firstOrNull { File(it, "AiRouter.kt").exists() }?.let { return it }
            d = d.parentFile
        }
        error("source नहीं मिला")
    }
    private fun read(n: String) = File(src(), n).readText()

    @Test fun oneKeyOnAndBackendReady_onlyThenServerOnly() {
        AiRouter.readyOverride = true
        Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, true) }
        assertTrue(AiRouter.chatServerOnly(c))
        Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, false) }
        assertFalse(AiRouter.chatServerOnly(c))
        // सर्वर वाला build ही नहीं = पहले जैसा (अपनी key)
        Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, true) }
        AiRouter.readyOverride = false
        assertFalse(AiRouter.chatServerOnly(c))
        AiRouter.chatServerOverride = true
        assertTrue(AiRouter.chatServerOnly(c))
        // पुराना नियम नहीं बदला: कोई काम backend से पहले नहीं
        assertFalse(AiRouter.backendFirst(AiRouter.Task.CHAT))
    }

    @Test fun ruleIsOneKeyAndBackend() {
        val r = read("AiRouter.kt")
        assertTrue(r.contains("fun chatServerOnly(c: Context): Boolean = chatServerOverride ?: (Prefs.oneKeyOn(c) && backendReady())"))
        // chat_chain सिर्फ़ चैट के काम पर, आवाज़ के टिकट वाले अनुरोध पर नहीं
        assertTrue(r.contains("if (chatChain && task == AiRouter.Task.CHAT && ticket == null) put(\"chat_chain\", true)"))
        assertTrue(r.contains("private val chatChain: Boolean = false"))
    }

    @Test fun streamChatGoesServerFirstOnlyForChat() {
        val bm = read("BrainManager.kt")
        val start = bm.indexOf("suspend fun streamChat(")
        assertTrue(start > 0)
        val head = bm.substring(start, bm.indexOf("var said = false", start))
        // ऑल-राउंडर चालू + DK Live से नहीं = सीधे सर्वर; अपनी keys (streamChatOwn) तक पहुँचता ही नहीं
        assertTrue(head.contains("if (!fromLive && AiRouter.chatServerOnly(ctx)) return@withContext serverOnlyChat(prompt, note, onDelta)"))
        val fn = bm.substring(bm.indexOf("private suspend fun serverOnlyChat("), bm.indexOf("private fun chatHist()"))
        assertTrue(fn.contains("BackendAiClient(ctx, AiRouter.Task.CHAT, chatChain = true)"))
        assertFalse(fn.contains("streamChatOwn"))
        assertFalse(fn.contains("chipPick"))
        assertFalse(fn.contains("Prefs.getKey"))
        // बंद वाला पुराना रास्ता जैसा का तैसा
        assertTrue(bm.contains("if (said || !AiRouter.fallback(e)) throw e"))
        assertTrue(bm.contains("AiRouter.client(ctx, if (fromLive) AiRouter.Task.LIVE else AiRouter.Task.CHAT)"))
    }

    @Test fun allFailedMessageShownAsIs() {
        val e = BackendWire.error(503, """{"ok":false,"code":"ALL_FAILED","error":"अभी जवाब नहीं आ पाया, थोड़ी देर बाद कोशिश करें।"}""")
        assertEquals("अभी जवाब नहीं आ पाया, थोड़ी देर बाद कोशिश करें।", AiRouter.say(e))
    }
}
