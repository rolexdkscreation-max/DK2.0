package com.dk.assistant

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v4.x (सुरक्षा): API keys / GitHub token / Worker token / चेहरे की पहचान फ़ोन में ताले (AES-GCM, AndroidKeyStore) के साथ।
 *
 * पहले ये सादे text में SharedPreferences में थे। अब [SecretPrefs] बीच में बैठता है:
 *  - लिखते समय (putString) राज़ वाला नाम हो तो value बंद (seal) होकर "dkenc1:..." बनती है
 *  - पढ़ते समय (getString / getAll) अपने-आप खुलती है - बाकी code (Prefs.ghToken(), Prefs.scopeKey() ...) बिल्कुल वैसा ही
 *  - पुरानी सादी value भी पढ़ी जाती है (पहली बार ऐप खुलने पर [sealAll] उसे बंद कर देता है)
 *  - Keystore न चले तो value सादी ही रहती है (token / key कभी गायब नहीं - कोडिंग एजेंट का push कभी नहीं रुके)
 */
object SecretNames {
    const val PREFIX = "dkenc1:"

    private val EXACT = setOf(
        "gh_token", "worker_token", "voice_key", "coder_key", "api_key", "one_key_gemini",
        "search_key_tavily", "search_key_exa", "owner_face_v2",
    )
    private val SCOPE_KEY = Regex("^(live|coder|pc)_key_.+")

    /** यह setting राज़ है? (key_gemini, live_key_groq, one_key_gemini, gh_token ...) */
    fun isSecret(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.startsWith("key_") || name in EXACT || SCOPE_KEY.matches(name)
    }

    fun isSealed(v: String?): Boolean = v != null && v.startsWith(PREFIX)
}

/** बंद / खोलने का तरीका - असली ऐप में [KeystoreCodec], tests में नकली */
interface SecretCodec {
    /** सादा -> बंद (null = नहीं हो पाया) */
    fun seal(plain: String): String?
    /** बंद -> सादा (null = नहीं खुला) */
    fun open(sealed: String): String?
}

/** AndroidKeyStore की चाबी (फ़ोन से बाहर नहीं निकलती), AES-256-GCM */
object KeystoreCodec : SecretCodec {
    private const val ALIAS = "dk_prefs_secret_v1"
    private val opened = ConcurrentHashMap<String, String>()   // हर बार Keystore न बुलाना पड़े (Live की तेज़ी)

    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    override fun seal(plain: String): String? = try {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
        val out = SecretNames.PREFIX + Base64.encodeToString(
            com.dk.assistant.connect.Box.pack(ci.iv, ci.doFinal(plain.toByteArray(Charsets.UTF_8))), Base64.NO_WRAP)
        opened[out] = plain
        out
    } catch (e: Exception) { DKLog.d("सुरक्षा: setting बंद नहीं हुई (${e.javaClass.simpleName}) - सादी रखी"); null }

    override fun open(sealed: String): String? {
        opened[sealed]?.let { return it }
        return try {
            val raw = Base64.decode(sealed.removePrefix(SecretNames.PREFIX), Base64.NO_WRAP)
            val (iv, ct) = com.dk.assistant.connect.Box.unpack(raw) ?: throw IllegalStateException("bad box")
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(ci.doFinal(ct), Charsets.UTF_8).also { opened[sealed] = it }
        } catch (e: Exception) { DKLog.d("सुरक्षा: setting खुली नहीं (${e.javaClass.simpleName})"); null }
    }
}

/** असली SharedPreferences के ऊपर ताले वाली परत - बाकी सब (bool, int, contains, listener) सीधा असली को */
class SecretPrefs(val base: SharedPreferences, private val codec: SecretCodec) : SharedPreferences {

    companion object {
        private val cache = java.util.IdentityHashMap<SharedPreferences, SecretPrefs>()

        /** इस prefs फ़ाइल की ताले वाली परत (हर बार वही). पहली बार पुरानी सादी keys/token बंद ([sealAll]) */
        fun of(raw: SharedPreferences): SecretPrefs = synchronized(cache) {
            cache[raw] ?: SecretPrefs(raw, KeystoreCodec).also { sp ->
                cache[raw] = sp
                try { sp.sealAll().takeIf { it > 0 }?.let { DKLog.d("सुरक्षा: $it keys/token ताले में रखीं") } } catch (_: Exception) {}
            }
        }
    }

    private fun read(name: String?, v: String?): String? =
        if (v != null && SecretNames.isSecret(name) && SecretNames.isSealed(v)) codec.open(v) else v

    private fun write(name: String?, v: String?): String? =
        if (v.isNullOrBlank() || !SecretNames.isSecret(name) || SecretNames.isSealed(v)) v else (codec.seal(v) ?: v)

    override fun getString(key: String?, defValue: String?): String? {
        val v = base.getString(key, null) ?: return defValue
        return read(key, v) ?: defValue   // चाबी खो गई (बहुत कम) = जैसे setting थी ही नहीं, बंद text कभी API को नहीं
    }

    override fun getAll(): MutableMap<String, *> =
        base.all.mapValuesTo(LinkedHashMap<String, Any?>()) { (k, v) -> if (v is String) read(k, v) else v }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = base.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int) = base.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long) = base.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float) = base.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean) = base.getBoolean(key, defValue)
    override fun contains(key: String?) = base.contains(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
        base.registerOnSharedPreferenceChangeListener(l)
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
        base.unregisterOnSharedPreferenceChangeListener(l)

    override fun edit(): SharedPreferences.Editor = Ed(base.edit())

    private inner class Ed(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { e.putString(key, write(key, value)) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { e.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { e.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { e.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun remove(key: String?) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    /** पुरानी सादी keys / token एक बार में बंद (जो बंद न हो पाए वह सादी ही रहे - कुछ नहीं खोता). कितनी बंद हुईं */
    fun sealAll(): Int {
        val todo = base.all.filter { (k, v) -> v is String && v.isNotBlank() && SecretNames.isSecret(k) && !SecretNames.isSealed(v) }
        if (todo.isEmpty()) return 0
        val e = base.edit()
        var n = 0
        todo.forEach { (k, v) -> codec.seal(v as String)?.let { e.putString(k, it); n++ } }
        if (n > 0) e.commit()
        return n
    }
}
