package com.dk.assistant.backend

import android.content.Context
import com.dk.assistant.Prefs
import com.dk.assistant.Quiet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 17 नवंबर 2026: Firebase पहचान (Spark - मुफ़्त). सिर्फ़ दो तरीके:
 *  - Google (मालिक) - Play services से Google का token -> Firebase `signInWithIdp`
 *  - Anonymous (मेहमान) - कुछ पूछे बिना, अपने-आप
 * token फ़ोन में ताले में (be_id_token / be_refresh_token). ऐप सिर्फ़ Firebase ID token भेजता है - कोई Worker key नहीं.
 */
object BackendSession {
    private const val ID = "be_id_token"
    private const val REFRESH = "be_refresh_token"
    private const val EXP = "be_id_exp"
    private const val KIND = "be_kind"          // "google" / "anon"
    private const val EMAIL = "be_email"
    private const val OWNER = "be_owner"
    private val http by lazy {
        com.dk.assistant.NetFix.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    fun kind(c: Context) = Prefs.str(c, KIND, "")
    fun email(c: Context) = Prefs.str(c, EMAIL, "")
    fun google(c: Context) = kind(c) == "google"
    /** backend ने मालिक माना? (आख़िरी /license/status से) - admin वाला हिस्सा सिर्फ़ तब */
    fun owner(c: Context) = google(c) && Prefs.str(c, OWNER, "") == "1"
    fun markOwner(c: Context, yes: Boolean) = Prefs.edit(c) { putString(OWNER, if (yes) "1" else "") }

    fun signOut(c: Context) = Prefs.edit(c) { remove(ID); remove(REFRESH); remove(EXP); remove(KIND); remove(EMAIL); remove(OWNER) }

    /** जाँचा हुआ ID token (5 मिनट से कम बचा = नया). कोई पहचान नहीं = मेहमान (Anonymous) अपने-आप */
    @Synchronized fun idToken(c: Context, force: Boolean = false): String {
        if (!BackendConfig.ready()) throw BackendException(0, BackendWire.NOT_CONFIGURED, "DK का सर्वर इस ऐप में नहीं जुड़ा")
        val now = System.currentTimeMillis()
        val tok = Prefs.raw(c, ID)
        if (!force && tok.isNotBlank() && Prefs.str(c, EXP, "0").toLongOrNull()?.let { it - now > 300_000 } == true) return tok
        val rt = Prefs.raw(c, REFRESH)
        if (rt.isNotBlank()) {
            try { return refresh(c, rt) } catch (e: BackendException) {
                if (e.reason != "TOKEN_EXPIRED" && e.reason != "INVALID_REFRESH_TOKEN" && e.reason != "USER_NOT_FOUND") throw e
                Quiet.note(e)
            }
        }
        return anonymous(c)
    }

    private fun idt(path: String) = "https://identitytoolkit.googleapis.com/v1/$path?key=" + BackendConfig.values().apiKey

    private fun post(url: String, body: okhttp3.RequestBody): JSONObject {
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            val o = try { JSONObject(b) } catch (_: Exception) { JSONObject() }
            if (!r.isSuccessful) {
                val m = o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "HTTP ${r.code}" }
                throw BackendException(r.code, m.substringBefore(' ').substringBefore(':'), firebaseHi(m))
            }
            return o
        }
    }

    internal fun firebaseHi(m: String) = when {
        "ADMIN_ONLY_OPERATION" in m || "OPERATION_NOT_ALLOWED" in m -> "Firebase में यह login तरीका चालू नहीं (Authentication → Sign-in method)"
        "API_KEY" in m || "API key" in m -> "Firebase की web API key गलत है (GitHub Secret FIREBASE_WEB_API_KEY देखें)"
        "INVALID_IDP_RESPONSE" in m -> "Google ने पहचान नहीं दी - Firebase में Google login चालू करें"
        else -> "लॉगिन नहीं हो पाया: " + m.take(80)
    }

    private fun save(c: Context, id: String, refresh: String, expiresIn: Long, kind: String?, email: String?) {
        Prefs.edit(c) {
            putString(ID, id); if (refresh.isNotBlank()) putString(REFRESH, refresh)
            putString(EXP, (System.currentTimeMillis() + expiresIn * 1000).toString())
            if (kind != null) putString(KIND, kind)
            if (email != null) putString(EMAIL, email)
        }
    }

    private fun anonymous(c: Context): String {
        val o = post(idt("accounts:signUp"), JSONObject().put("returnSecureToken", true).toString().toRequestBody(JSON))
        save(c, o.getString("idToken"), o.optString("refreshToken"), o.optLong("expiresIn", 3600), "anon", "")
        markOwner(c, false)
        return o.getString("idToken")
    }

    private fun refresh(c: Context, rt: String): String {
        val o = post("https://securetoken.googleapis.com/v1/token?key=" + BackendConfig.values().apiKey,
            ("grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(rt, "UTF-8")).toRequestBody(FORM))
        val id = o.optString("id_token").ifBlank { o.optString("idToken") }
        save(c, id, o.optString("refresh_token"), o.optLong("expires_in", 3600), null, null)
        return id
    }

    /** Google का access token (Play services से) -> Firebase पहचान. मेहमान वाला डेटा नहीं जुड़ता (अलग uid). */
    fun signInGoogle(c: Context, googleAccessToken: String): String {
        if (!BackendConfig.ready()) throw BackendException(0, BackendWire.NOT_CONFIGURED, "DK का सर्वर इस ऐप में नहीं जुड़ा")
        val dom = BackendConfig.values().authDomain.ifBlank { BackendConfig.values().projectId + ".firebaseapp.com" }
        val body = JSONObject()
            .put("postBody", "access_token=" + java.net.URLEncoder.encode(googleAccessToken, "UTF-8") + "&providerId=google.com")
            .put("requestUri", "https://$dom").put("returnSecureToken", true).put("returnIdpCredential", true)
        val o = post(idt("accounts:signInWithIdp"), body.toString().toRequestBody(JSON))
        save(c, o.getString("idToken"), o.optString("refreshToken"), o.optLong("expiresIn", 3600), "google", o.optString("email"))
        return o.optString("email")
    }

    val GOOGLE_SCOPES = listOf("openid", "email", "profile")
}
