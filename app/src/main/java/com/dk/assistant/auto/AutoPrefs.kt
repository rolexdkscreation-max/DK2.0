package com.dk.assistant.auto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ऑटोनोमस मोड की सेटिंग्स. शुरू में सब बंद (master switch = false) -> ऐप ठीक पहले जैसा.
 * अलग फ़ाइल ("dk_auto") - DK की बाकी सेटिंग्स को नहीं छूती.
 */
object AutoPrefs {
    private const val FILE = "dk_auto"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun on(c: Context) = sp(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()

    /** हर तरह के काम का अपना switch (master चालू हो तभी असर) */
    fun kindOn(c: Context, k: AutoPlan.Kind) = sp(c).getBoolean("kind_${k.id}", true)
    fun setKindOn(c: Context, k: AutoPlan.Kind, v: Boolean) = sp(c).edit().putBoolean("kind_${k.id}", v).apply()
    fun enabledKinds(c: Context): Set<AutoPlan.Kind> = AutoPlan.Kind.values().filter { it != AutoPlan.Kind.CUSTOM && kindOn(c, it) }.toSet()

    /** भेजना / पोस्ट / डिलीट से पहले पूछो - शुरू में चालू */
    fun confirm(c: Context) = sp(c).getBoolean("confirm", true)
    fun setConfirm(c: Context, v: Boolean) = sp(c).edit().putBoolean("confirm", v).apply()

    // ---------------------------------------------------------------- अपने जोड़े काम
    fun customTasks(c: Context): List<AutoPlan.CustomTask> = try {
        val a = JSONArray(sp(c).getString("custom", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            AutoPlan.CustomTask(it.optString("id"), it.optString("name"), it.optString("ins"), it.optBoolean("on", true))
        }
    } catch (_: Exception) { emptyList() }

    fun saveCustom(c: Context, list: List<AutoPlan.CustomTask>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("ins", it.instructions).put("on", it.on)) }
        sp(c).edit().putString("custom", a.toString()).apply()
    }

    // ---------------------------------------------------------------- बंद (encrypted) keys
    const val KEY_AUTO = "auto_ai"
    const val KEY_CODER = "auto_coder"
    private const val ALIAS = "dk_auto_key"

    fun secret(c: Context, name: String): String = sp(c).getString("s_$name", null)?.let { open(it) }.orEmpty()
    fun setSecret(c: Context, name: String, v: String?) {
        val e = sp(c).edit()
        if (v.isNullOrBlank()) e.remove("s_$name") else seal(v.trim())?.let { e.putString("s_$name", it) }
        e.apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun seal(plain: String): String? = try {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(com.dk.assistant.connect.Box.pack(ci.iv, ci.doFinal(plain.toByteArray(Charsets.UTF_8))), Base64.NO_WRAP)
    } catch (e: Exception) { com.dk.assistant.DKLog.d("ऑटोनोमस: key बंद नहीं हुई (${e.javaClass.simpleName})"); null }

    private fun open(stored: String): String? = try {
        val (iv, ct) = com.dk.assistant.connect.Box.unpack(Base64.decode(stored, Base64.NO_WRAP)) ?: throw IllegalStateException("bad box")
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(ci.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) { com.dk.assistant.DKLog.d("ऑटोनोमस: key खुली नहीं (${e.javaClass.simpleName})"); null }
}
