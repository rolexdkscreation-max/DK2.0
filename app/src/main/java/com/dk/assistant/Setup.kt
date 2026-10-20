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

    /** System settings me DK ki accessibility ON hai? (service abhi restart ho rahi ho tab bhi true) */
    fun accessibilityOn(c: Context): Boolean = DKAccessibilityService.instance != null || enabledInSettings(c)

    private fun component(c: Context) = android.content.ComponentName(c, DKAccessibilityService::class.java).flattenToString()

    fun enabledInSettings(c: Context): Boolean = try {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = component(c); val short = me.replace(c.packageName + "/" + c.packageName, c.packageName + "/")
        list.split(':').any { it.equals(me, true) || it.equals(short, true) }
    } catch (e: Exception) { false }

    /** adb se "WRITE_SECURE_SETTINGS" mili ho to DK khud apni accessibility ON kar leta hai */
    fun canWriteSecure(c: Context) =
        ContextCompat.checkSelfPermission(c, "android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun selfEnableAccessibility(c: Context): Boolean {
        if (DKAccessibilityService.instance != null) return true
        if (!canWriteSecure(c)) return false
        return try {
            val cr = c.contentResolver
            val me = component(c)
            val list = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName + "/") }
            // band karke dobara ON = Android service ko phir se jod deta hai
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            DKLog.d("Accessibility khud ON kiya"); true
        } catch (e: Exception) { DKLog.d("selfEnable: ${e.message}"); false }
    }

    // ---- battery: Android DK ko band na kare
    fun batteryExempt(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    } catch (e: Exception) { false }

    fun requestBatteryExempt(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

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
