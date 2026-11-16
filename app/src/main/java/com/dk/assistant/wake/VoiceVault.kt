package com.dk.assistant.wake

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 16 नवंबर 2026 (मालिक: "वॉइसप्रिंट सिर्फ़ फ़ोन में, encrypted"): Android Keystore की AES-256-GCM चाबी से voiceprint बंद.
 * चाबी फ़ोन के सुरक्षित हिस्से (TEE) में बनती है, ऐप से बाहर नहीं निकलती, इंटरनेट पर कभी नहीं जाती, backup में नहीं.
 * (यह Live की API key नहीं - कोई key माँगी / डाली नहीं जाती.)
 *
 * लिखा रूप: "vv1:" + base64(12 byte IV + ciphertext). पुराना सादा रूप पढ़ने पर भी चलता है (और अगली बार बंद होकर सेव).
 * Keystore न चले (बहुत पुराना / टूटा फ़ोन, JVM test) = सादा ही रहता है, ऐप नहीं रुकता.
 */
object VoiceVault {
    private const val ALIAS = "dk_voiceprint_v1"
    const val PREFIX = "vv1:"

    @Volatile var broken = false; private set

    private fun key(): SecretKey? {
        if (broken) return null
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
        } catch (_: Throwable) { broken = true; null }
    }

    fun sealed(s: String?) = s != null && s.startsWith(PREFIX)

    /** सादा -> बंद. Keystore न हो = वैसा ही */
    fun seal(plain: String?): String? {
        if (plain == null || sealed(plain)) return plain
        val k = key() ?: return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (_: Throwable) { plain }
    }

    /** बंद -> सादा (सादा हो तो वैसा ही). न खुले = null (तब voiceprint नहीं माना जाता, दोबारा दर्ज करना होगा) */
    fun open(stored: String?): String? {
        if (stored == null || !sealed(stored)) return stored
        val k = key() ?: return null
        return try {
            val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (all.size < 13) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12)) }
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (_: Throwable) { null }
    }
}
