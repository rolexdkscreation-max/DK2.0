package com.dk.assistant.license

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.dk.assistant.DKLog
import com.dk.assistant.UiLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DK AI Part 13: access key (एडमिन = AssistantBrand.OWNER_NAME).
 * backend का पता git में नहीं - `app/src/main/assets/dk_license.json` (git-ignored, docs/LICENSING.md देखें).
 * फ़ाइल नहीं = "licensing not configured" = ऐप पहले जैसा पूरा चलता है.
 * key कभी log नहीं होती; device id सीधे नहीं, hash भेजा जाता है.
 */
object AccessKeyManager {
    private const val PREFS = "dk_access_key"
    private const val CONFIG_ASSET = "dk_license.json"

    private val _info = MutableStateFlow(LicenseInfo(true, "", 0L, "", false, false, KeyStatus.NOT_CONFIGURED))
    val info: StateFlow<LicenseInfo> = _info

    @Volatile private var endpoint: String? = null
    @Volatile private var configLoaded = false

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun endpoint(c: Context): String? {
        if (!configLoaded) {
            endpoint = try { c.assets.open(CONFIG_ASSET).bufferedReader().use { AccessKeyRules.parseConfig(it.readText()) } } catch (_: Exception) { null }
            configLoaded = true
        }
        return endpoint
    }

    fun configured(c: Context) = endpoint(c) != null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshInfo(c: Context): LicenseInfo {
        val p = prefs(c)
        val i = AccessKeyRules.info(
            configured(c), p.getString("key", null), p.getString("plan", "").orEmpty(), p.getLong("expires_at", 0L),
            p.getBoolean("active", false), p.getBoolean("blocked", false), System.currentTimeMillis(), UiLang.en(),
        )
        _info.value = i
        return i
    }

    /** DK लाइव / कमांड से पहले: null = चलो, वरना बोलने लायक मना करने का संदेश */
    fun refusalOrNull(c: Context): String? {
        val i = refreshInfo(c)
        return if (i.active) null else AccessKeyRules.refusal(i.status, UiLang.en())
    }

    @SuppressLint("HardwareIds")
    private fun device(c: Context): String =
        AccessKeyRules.deviceHash(Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    private fun call(url: String, action: String, key: String, dev: String): RemoteResult? {
        val body = JSONObject().put("action", action).put("key", key).put("device", dev).toString()
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string()
            // 4xx में भी backend {"ok":false,...} भेज सकता है
            return AccessKeyRules.parseRemote(text) ?: if (r.code in 400..499) RemoteResult(false, false, "", 0L, false, "") else null
        }
    }

    private fun save(c: Context, key: String, r: RemoteResult) {
        prefs(c).edit().putString("key", key).putString("plan", r.plan).putLong("expires_at", r.expiresAt)
            .putBoolean("active", r.ok && r.active).putBoolean("blocked", r.blocked)
            .putLong("last_remote_check", System.currentTimeMillis()).apply()
    }

    /** नई key डालो: backend से जाँच + इस फ़ोन से जोड़ो. return = यूज़र को दिखाने लायक संदेश */
    suspend fun activate(c: Context, raw: String): String = withContext(Dispatchers.IO) {
        val url = endpoint(c) ?: return@withContext refreshInfo(c).statusText
        val key = AccessKeyRules.normalizeKey(raw)
            ?: return@withContext UiLang.s("key सही रूप में नहीं है (सिर्फ़ A-Z, 0-9 और -)")
        val r = try { call(url, "activate", key, device(c)) } catch (e: Exception) {
            DKLog.d("Access key: activate नेटवर्क गलती ${e.javaClass.simpleName}")
            return@withContext UiLang.s("इंटरनेट/सर्वर से जुड़ नहीं पाया, थोड़ी देर बाद कोशिश करें")
        } ?: return@withContext UiLang.s("सर्वर का जवाब समझ नहीं आया")
        save(c, key, r)
        val i = refreshInfo(c)
        DKLog.d("Access key: activate -> ${i.status}")
        if (i.active) i.statusText else r.message.ifBlank { i.statusText }
    }

    /**
     * 3 घंटे में एक बार backend से दोबारा जाँच. नेटवर्क गलती = पुरानी हालत रहती है (FAIL OPEN).
     * एडमिन ने block किया = तुरंत बंद. return = ताज़ा हालत.
     */
    suspend fun liveCheck(c: Context, force: Boolean = false): LicenseInfo = withContext(Dispatchers.IO) {
        val url = endpoint(c) ?: return@withContext refreshInfo(c)
        val p = prefs(c)
        val key = p.getString("key", null)
        if (key.isNullOrBlank() || !AccessKeyRules.shouldLiveCheck(p.getLong("last_remote_check", 0L), System.currentTimeMillis(), force))
            return@withContext refreshInfo(c)
        val r = try { call(url, "check", key, device(c)) } catch (e: Exception) {
            DKLog.d("Access key: जाँच नहीं हुई (${e.javaClass.simpleName}) - पुरानी हालत रखी")
            null
        }
        if (r != null) save(c, key, r)
        refreshInfo(c)
    }

    fun forget(c: Context) {
        prefs(c).edit().clear().apply()
        refreshInfo(c)
    }
}
