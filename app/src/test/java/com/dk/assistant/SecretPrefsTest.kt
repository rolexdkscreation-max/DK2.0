package com.dk.assistant

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** सुरक्षा: API keys / GitHub token / Worker token / चेहरा ताले में - बर्ताव की जाँच (नकली ताला + नकली prefs) */
class SecretPrefsTest {

    /** फ़ोन के बाहर SharedPreferences जैसा ही (सिर्फ़ याद में) */
    private class MemPrefs : SharedPreferences {
        val m = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(m)
        override fun getString(k: String?, d: String?) = (m[k] as? String) ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = (m[k] as? MutableSet<String>) ?: d
        override fun getInt(k: String?, d: Int) = (m[k] as? Int) ?: d
        override fun getLong(k: String?, d: Long) = (m[k] as? Long) ?: d
        override fun getFloat(k: String?, d: Float) = (m[k] as? Float) ?: d
        override fun getBoolean(k: String?, d: Boolean) = (m[k] as? Boolean) ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val todo = LinkedHashMap<String, Any?>(); val gone = HashSet<String>(); var wipe = false
            override fun putString(k: String?, v: String?) = apply { todo[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { todo[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { todo[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { todo[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { todo[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { todo[k!!] = v }
            override fun remove(k: String?) = apply { gone += k!! }
            override fun clear() = apply { wipe = true }
            override fun commit(): Boolean { if (wipe) m.clear(); gone.forEach { m.remove(it) }; m.putAll(todo); return true }
            override fun apply() { commit() }
        }
    }

    /** नकली ताला: उल्टा करके छिपाता है (असली में AndroidKeyStore AES-GCM) */
    private class FakeCodec(var works: Boolean = true) : SecretCodec {
        override fun seal(plain: String) = if (works) SecretNames.PREFIX + plain.reversed() else null
        override fun open(sealed: String) = if (works) sealed.removePrefix(SecretNames.PREFIX).reversed() else null
    }

    @Test fun secretNames() {
        listOf("key_gemini", "key_groq", "key_tavily", "live_key_gemini", "coder_key_gemini5", "pc_key_openrouter",
            "one_key_gemini", "gh_token", "worker_token", "voice_key", "coder_key", "api_key",
            "live_key_caption", "live_key_search", "search_key_tavily", "search_key_exa", "owner_face_v2")
            .forEach { assertTrue(it, SecretNames.isSecret(it)) }
        listOf("model_gemini", "gemini_voice", "one_key_on", "ui_lang", "owner_face_model", "gh_repo", "orb_style", "", null)
            .forEach { assertFalse("$it", SecretNames.isSecret(it)) }
    }

    @Test fun secretIsLockedOnDiskButReadsPlain() {
        val raw = MemPrefs(); val p = SecretPrefs(raw, FakeCodec())
        p.edit().putString("gh_token", "ghp_abc123").putString("key_gemini", "AIzaXYZ").putString("gh_repo", "me/DK2.0").commit()
        // फ़ाइल में सादा token नहीं
        assertTrue(SecretNames.isSealed(raw.getString("gh_token", null)))
        assertFalse(raw.getString("gh_token", null)!!.contains("ghp_abc123"))
        assertFalse(raw.getString("key_gemini", null)!!.contains("AIzaXYZ"))
        assertEquals("me/DK2.0", raw.getString("gh_repo", null))                 // आम setting वैसी ही
        // पढ़ने वाले (Prefs.ghToken / scopeKey / कोडिंग एजेंट) को वही सादा token
        assertEquals("ghp_abc123", p.getString("gh_token", ""))
        assertEquals("AIzaXYZ", p.getString("key_gemini", ""))
        assertEquals("AIzaXYZ", p.all["key_gemini"])
        assertEquals("me/DK2.0", p.getString("gh_repo", ""))
    }

    @Test fun oldPlainKeysStillWorkAndGetSealedOnce() {
        val raw = MemPrefs()
        raw.edit().putString("gh_token", "ghp_old").putString("live_key_gemini", "AIzaOld").putString("gh_repo", "x/y")
            .putBoolean("one_key_on", true).commit()
        val p = SecretPrefs(raw, FakeCodec())
        assertEquals("ghp_old", p.getString("gh_token", ""))                    // ताले से पहले भी पढ़ा जाए
        assertEquals(2, p.sealAll())
        assertTrue(SecretNames.isSealed(raw.getString("gh_token", null)))
        assertTrue(SecretNames.isSealed(raw.getString("live_key_gemini", null)))
        assertEquals("x/y", raw.getString("gh_repo", null))
        assertEquals(true, raw.getBoolean("one_key_on", false))
        assertEquals("ghp_old", p.getString("gh_token", ""))
        assertEquals("AIzaOld", p.getString("live_key_gemini", ""))
        assertEquals(0, p.sealAll())                                              // दोबारा कुछ नहीं
    }

    @Test fun keystoreBrokenNeverLosesToken() {
        val raw = MemPrefs(); val codec = FakeCodec(works = false); val p = SecretPrefs(raw, codec)
        p.edit().putString("gh_token", "ghp_keep").commit()
        assertEquals("ghp_keep", raw.getString("gh_token", null))                // ताला नहीं चला = सादा, पर गायब नहीं
        assertEquals("ghp_keep", p.getString("gh_token", ""))
        assertEquals(0, p.sealAll())
        assertEquals("ghp_keep", p.getString("gh_token", ""))
    }

    @Test fun lostKeyNeverSendsLockedTextToApi() {
        val raw = MemPrefs(); val codec = FakeCodec(); val p = SecretPrefs(raw, codec)
        p.edit().putString("key_gemini", "AIzaZ").commit()
        codec.works = false                                                       // चाबी खो गई (बहुत कम)
        assertEquals("", p.getString("key_gemini", ""))                           // बंद text कभी key बनकर नहीं जाता
        assertNull(p.getString("key_gemini", null))
        assertNull(p.all["key_gemini"])
    }

    @Test fun blankAndRemoveStayPlain() {
        val raw = MemPrefs(); val p = SecretPrefs(raw, FakeCodec())
        p.edit().putString("gh_token", "").commit()
        assertEquals("", raw.getString("gh_token", null))
        p.edit().putString("gh_token", "t").commit(); p.edit().remove("gh_token").commit()
        assertFalse(raw.contains("gh_token"))
    }

    @Test fun workerNeedsToken_andLiveKeyOnlyInHeader() {
        assertTrue(com.dk.assistant.voice.engine.WorkerWhy.http(500, "APP_TOKEN secret नहीं लगी").contains("APP_TOKEN"))
        assertTrue(com.dk.assistant.voice.engine.WorkerWhy.http(500, "GEMINI_API_KEY missing").contains("GEMINI_API_KEY"))
        for (f in listOf("GeminiLiveRace.kt", "LiveCaption.kt", "voice/engine/LiveNativeEngine.kt")) {
            val s = src(f)
            assertFalse(f, s.contains("?key="))
            assertTrue(f, s.contains(".header(\"x-goog-api-key\", "))
        }
    }

    private fun src(p: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$p"), File(d, "app/src/main/java/com/dk/assistant/$p")).firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$p नहीं मिली")
    }

    @Test fun wiring_prefsAndVoiceUseLock_faceNoLongerEncryptedWithCodeKey() {
        val prefs = src("Prefs.kt")
        assertTrue(prefs.contains("secure(c.getSharedPreferences(\"dk_prefs\", Context.MODE_PRIVATE))"))
        assertTrue(src("voice/engine/VoiceEngines.kt").contains("SecretPrefs.of("))
        assertTrue(src("SecretPrefs.kt").contains("\"AndroidKeyStore\""))
        // चेहरा: नई पहचान अब code वाली चाबी से कभी बंद नहीं होती (वह सिर्फ़ पुरानी पढ़ने के लिए)
        assertFalse(prefs.contains("Cipher.ENCRYPT_MODE"))
        assertTrue(prefs.contains("putString(FACE_PREF, "))
    }
}
