package com.dk.assistant

import android.content.Context

/** App ki settings (API key, model, options) phone me save karta hai. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun model(c: Context): String =
        (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
    fun wakeMode(c: Context): Boolean = sp(c).getBoolean("wake_mode", false)
    fun screenshot(c: Context): Boolean = sp(c).getBoolean("screenshot", true)

    fun save(c: Context, key: String, model: String, wake: Boolean, shot: Boolean) {
        sp(c).edit()
            .putString("api_key", key.trim())
            .putString("model", model.trim())
            .putBoolean("wake_mode", wake)
            .putBoolean("screenshot", shot)
            .apply()
    }
}
