package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.ApiScopes
import com.dk.assistant.CoderAi
import com.dk.assistant.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 13 नवंबर 2026: कोडिंग एजेंट की chain में सच में बाकी हिस्सों की keys (NVIDIA / OpenRouter) आती हैं */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoderFallbackBehaviorTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()
    private val keys = listOf(ApiScopes.pref(ApiScopes.CODER, "key", "gemini"), ApiScopes.pref(ApiScopes.PC, "key", "nvidia"),
        ApiScopes.pref(ApiScopes.CHAT, "key", "openrouter"))

    @After fun clean() = Prefs.edit(c) { keys.forEach { remove(it) }; putBoolean(ApiScopes.ONE_KEY_ON, false) }

    @Test fun chainHasEveryProvidedApi() {
        Prefs.edit(c) {
            putBoolean(ApiScopes.ONE_KEY_ON, false)
            putString(keys[0], "AIzaCODER_test"); putString(keys[1], "nvapi-PC_test"); putString(keys[2], "sk-or-CHAT_test")
        }
        val ai = CoderAi.forCoder(c) { _, _ -> }
        assertNotNull(ai)
        val all = listOf(ai!!) + ai.fallbackList()
        assertEquals("gemini", ai.provider.id)
        assertTrue(all.any { it.provider.id == "nvidia" && it.keyForChain() == "nvapi-PC_test" })
        assertTrue(all.any { it.provider.id == "openrouter" && it.keyForChain() == "sk-or-CHAT_test" })
        // OpenRouter (CROSS_ORDER में पहले) NVIDIA से पहले
        assertTrue(all.indexOfFirst { it.provider.id == "openrouter" } < all.indexOfFirst { it.provider.id == "nvidia" })
    }

    @Test fun noCoderKey_stillWorksWithOtherKeys() {
        Prefs.edit(c) { putBoolean(ApiScopes.ONE_KEY_ON, false); putString(keys[1], "nvapi-PC_test") }
        val ai = CoderAi.forCoder(c) { _, _ -> }
        assertNotNull("कोडिंग एजेंट की अपनी key नहीं - फिर भी DK कंप्यूटर वाली NVIDIA key से चले", ai)
        assertEquals("nvidia", ai!!.provider.id)
    }
}
