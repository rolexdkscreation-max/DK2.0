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

    fun computerOn(context: Context): Boolean = getComputerOn(context)
    fun coderOn(context: Context): Boolean = getCoderOn(context)
    fun coderSeven(context: Context): Boolean = getCoderSeven(context)
    fun coderTeam(context: Context): Boolean = getCoderTeam(context)
    fun screenshot(context: Context): Boolean = getScreenshot(context)
    fun voiceGender(context: Context): String = getVoiceGender(context)
    fun activeProvider(context: Context): String = getActiveProvider(context)
    fun fallback(context: Context): String = getString(context, "fallback", "")
    fun key(context: Context): String = getString(context, "key", "")
    fun webAnswers(context: Context): Boolean = getBoolean(context, "web_answers", false)
    fun tavilySearchKey(context: Context): String = getString(context, "tavily_search_key", "")
    fun exaSearchKey(context: Context): String = getString(context, "exa_search_key", "")
    fun assistantName(context: Context): String = getString(context, "assistant_name", "DK")
    fun userName(context: Context): String = getString(context, "user_name", "User")
    fun searchOwnKey(context: Context): Boolean = getBoolean(context, "search_own_key", false)
    fun model(context: Context): String = getString(context, "model", "")
    fun personality(context: Context): String = getString(context, "personality", "")
    fun friendMode(context: Context): Boolean = getBoolean(context, "friend_mode", false)
    fun dkOnly(context: Context): Boolean = getBoolean(context, "dk_only", false)
    fun ttsGeminiKey(context: Context): String = getString(context, "tts_gemini_key", "")
    fun oneKeyOn(context: Context): Boolean = getBoolean(context, "one_key_on", false)
    fun scopeKey(context: Context): String = getString(context, "scope_key", "")
    fun scopeModel(context: Context): String = getString(context, "scope_model", "")
    fun fastModels(context: Context): Boolean = getBoolean(context, "fast_models", false)
    fun scopeBase(context: Context): String = getString(context, "scope_base", "")
    fun scopeBackups(context: Context): Boolean = getBoolean(context, "scope_backups", false)
    fun hasAnyKey(context: Context): Boolean = key(context).isNotBlank()
    fun coderThread(context: Context): String = getString(context, "coder_thread", "")
    fun ghRepo(context: Context): String = getString(context, "gh_repo", "")
    fun edit(context: Context): Boolean = getBoolean(context, "edit", false)
}