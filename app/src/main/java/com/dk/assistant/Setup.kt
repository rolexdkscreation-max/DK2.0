package com.dk.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permissions aur system settings kholne ke chhote helpers. */
object Setup {
    fun hasMic(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn() = DKAccessibilityService.instance != null

    fun openAccessibility(c: Context) =
        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openAppInfo(c: Context) = c.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun openTts(c: Context) = try {
        c.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    fun openUrl(c: Context, url: String) = try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    fun startKeepAlive(c: Context) {
        if (hasMic(c)) try { ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java)) }
        catch (e: Exception) { DKLog.d("KeepAlive: ${e.message}") }
    }
}
