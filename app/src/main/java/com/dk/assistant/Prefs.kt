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
}