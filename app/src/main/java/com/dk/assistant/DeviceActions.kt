package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/** Phone ke seedhe kaam: torch, settings pages, volume, media. */
object DeviceActions {

    @Volatile private var torchOn = false
    private var torchWatch = false

    fun torch(ctx: Context, on: Boolean?): String {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return "ERROR: camera service nahi"
        if (!torchWatch) {
            torchWatch = true
            try {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (_: Exception) {}
        }
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "ERROR: is phone me flash nahi mila"
        val target = on ?: !torchOn
        return try {
            cm.setTorchMode(id, target); torchOn = target
            if (target) "torch ON" else "torch OFF"
        } catch (e: Exception) { "ERROR: torch: ${e.message} (camera koi aur app use kar raha ho sakta hai)" }
    }

    private fun settingsAction(page: String): String? {
        val p = page.lowercase()
        fun has(vararg w: String) = w.any { p.contains(it) }
        return when {
            has("wifi", "wi-fi", "वाई", "वाइ") -> Settings.ACTION_WIFI_SETTINGS
            has("bluetooth", "ब्लूटूथ") -> Settings.ACTION_BLUETOOTH_SETTINGS
            has("hotspot", "tether", "हॉटस्पॉट") -> "android.settings.TETHER_SETTINGS"
            has("mobile data", "data usage", "डेटा", "sim", "सिम", "network", "नेटवर्क") -> Settings.ACTION_DATA_ROAMING_SETTINGS
            has("airplane", "flight", "एयरप्लेन", "फ्लाइट") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            has("display", "brightness", "डिस्प्ले", "ब्राइटनेस", "dark", "डार्क", "font", "फ़ॉन्ट", "फॉन्ट") -> Settings.ACTION_DISPLAY_SETTINGS
            has("sound", "volume", "ring", "साउंड", "आवाज़", "रिंग") -> Settings.ACTION_SOUND_SETTINGS
            has("dnd", "disturb", "डिस्टर्ब") -> "android.settings.ZEN_MODE_SETTINGS"
            has("notification", "नोटिफिकेशन") -> "android.settings.NOTIFICATION_SETTINGS"
            has("battery", "बैटरी") -> Intent.ACTION_POWER_USAGE_SUMMARY
            has("location", "gps", "लोकेशन", "जीपीएस") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            has("storage", "स्टोरेज", "memory", "मेमोरी") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            has("default app", "डिफ़ॉल्ट", "डिफॉल्ट") -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            has("app", "ऐप", "एप") -> Settings.ACTION_APPLICATION_SETTINGS
            has("about", "phone info", "फोन के बारे", "अबाउट", "android version") -> Settings.ACTION_DEVICE_INFO_SETTINGS
            has("date", "time", "समय", "तारीख", "टाइम") -> Settings.ACTION_DATE_SETTINGS
            has("language", "भाषा", "लैंग्वेज") -> Settings.ACTION_LOCALE_SETTINGS
            has("keyboard", "कीबोर्ड") -> Settings.ACTION_INPUT_METHOD_SETTINGS
            has("accessibility", "एक्सेसिबिलिटी", "सुलभता") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            has("security", "lock", "password", "fingerprint", "सिक्योरिटी", "लॉक", "पासवर्ड", "फिंगरप्रिंट") -> Settings.ACTION_SECURITY_SETTINGS
            has("privacy", "प्राइवेसी") -> Settings.ACTION_PRIVACY_SETTINGS
            has("developer", "डेवलपर") -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            has("nfc") -> Settings.ACTION_NFC_SETTINGS
            has("vpn") -> Settings.ACTION_VPN_SETTINGS
            has("cast", "कास्ट", "screen mirror") -> Settings.ACTION_CAST_SETTINGS
            has("account", "अकाउंट", "sync") -> Settings.ACTION_SYNC_SETTINGS
            has("wallpaper", "वॉलपेपर") -> Intent.ACTION_SET_WALLPAPER
            has("internet", "इंटरनेट", "wireless") -> Settings.ACTION_WIRELESS_SETTINGS
            else -> null
        }
    }

    fun openSettings(ctx: Context, page: String): String {
        val action = settingsAction(page)
        return try {
            ctx.startActivity(Intent(action ?: Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (action == null) "opened main Settings (page '$page' ke liye Settings ka search box use karo)"
            else "opened settings: $page"
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened main Settings (seedha page nahi khula; search box use karo)"
        }
    }

    /** Android 10+ ka chhota panel (internet / volume / wifi / nfc). */
    fun panel(ctx: Context, name: String): String {
        if (Build.VERSION.SDK_INT < 29) return openSettings(ctx, name)
        val a = when {
            name.contains("volume") -> Settings.Panel.ACTION_VOLUME
            name.contains("wifi") -> Settings.Panel.ACTION_WIFI
            name.contains("nfc") -> Settings.Panel.ACTION_NFC
            else -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        }
        return try { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "opened $name panel" }
        catch (e: Exception) { openSettings(ctx, name) }
    }

    fun volume(ctx: Context, dir: String, level: Int = -1): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "ERROR"
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        when (dir.lowercase()) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
            "set" -> am.setStreamVolume(s, (max * level.coerceIn(0, 100) / 100), AudioManager.FLAG_SHOW_UI)
            "silent" -> try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
            "ring", "normal" -> try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
        }
        return "volume ${am.getStreamVolume(s) * 100 / max}%"
    }

    fun media(ctx: Context, key: String): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "ERROR"
        val code = when (key.lowercase()) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return "media $key"
    }
}
