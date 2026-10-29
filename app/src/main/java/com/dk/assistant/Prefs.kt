package com.dk.assistant

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREF_NAME = "dk_assistant_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getString(context: Context, key: String, defaultVal: String = ""): String {
        return getPrefs(context).getString(key, defaultVal) ?: defaultVal
    }

    fun setString(context: Context, key: String, value: String) {
        getPrefs(context).edit().putString(key, value).apply()
    }

    fun getBoolean(context: Context, key: String, defaultVal: Boolean = false): Boolean {
        return getPrefs(context).getBoolean(key, defaultVal)
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }

    fun getSp(context: Context): Boolean = getBoolean(context, "sp", false)
    fun setSp(context: Context, value: Boolean) = setBoolean(context, "sp", value)

    fun getComputerOn(context: Context): Boolean = getBoolean(context, "computer_on", false)
    fun setComputerOn(context: Context, value: Boolean) = setBoolean(context, "computer_on", value)

    fun getCoderOn(context: Context): Boolean = getBoolean(context, "coder_on", false)
    fun setCoderOn(context: Context, value: Boolean) = setBoolean(context, "coder_on", value)

    fun getCoderSeven(context: Context): Boolean = getBoolean(context, "coder_seven", false)
    fun setCoderSeven(context: Context, value: Boolean) = setBoolean(context, "coder_seven", value)

    fun getCoderTeam(context: Context): Boolean = getBoolean(context, "coder_team", false)
    fun setCoderTeam(context: Context, value: Boolean) = setBoolean(context, "coder_team", value)

    fun getScreenshot(context: Context): Boolean = getBoolean(context, "screenshot", false)
    fun setScreenshot(context: Context, value: Boolean) = setBoolean(context, "screenshot", value)

    fun getVoiceGender(context: Context): String = getString(context, "voice_gender", "female")
    fun setVoiceGender(context: Context, value: String) = setString(context, "voice_gender", value)

    fun getActiveProvider(context: Context): String = getString(context, "active_provider", "default")
    fun setActiveProvider(context: Context, value: String) = setString(context, "active_provider", value)
}