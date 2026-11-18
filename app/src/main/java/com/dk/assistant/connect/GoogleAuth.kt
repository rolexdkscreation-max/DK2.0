package com.dk.assistant.connect

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * v3.72: Google login - Google Identity Services का AuthorizationClient.
 * - client secret ऐप में नहीं (Android OAuth client = package नाम + SHA-1 से पहचान).
 * - एक बार "Allow" के बाद token चुपचाप (silent) मिलता है; हम token सहेजते नहीं, हर बार Play services से माँगते हैं.
 * - हर कनेक्टर अपने ही scope माँगता है (incremental) - Gmail जोड़ा तो सिर्फ़ Gmail की इजाज़त.
 */
object GoogleAuth {
    sealed class Res {
        data class Token(val value: String) : Res()
        /** user की "Allow" स्क्रीन ज़रूरी - Settings से [PendingIntent] चलाओ */
        data class NeedsUi(val intent: PendingIntent?) : Res()
        data class Fail(val message: String) : Res()
    }

    fun request(scopes: List<String>): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    /** बिना UI (Live tool से, background thread पर). UI चाहिए = NeedsUi */
    fun tokenBlocking(c: Context, scopes: List<String>): Res = try {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(scopes)), 20, TimeUnit.SECONDS)
        read(r)
    } catch (e: Exception) {
        com.dk.assistant.DKBug.report("connector", e, "Google से अनुमति लेना")
        Res.Fail(explain(e))
    }

    fun read(r: AuthorizationResult): Res = when {
        r.hasResolution() -> Res.NeedsUi(r.pendingIntent)
        !r.accessToken.isNullOrBlank() -> Res.Token(r.accessToken!!)
        else -> Res.Fail("Google ने token नहीं दिया")
    }

    const val INTERNAL_HI = "Google सेटअप अधूरा (code 8): Firebase वाले Google Cloud project में Clients → Create client → Android बनाइए - " +
        "package com.dk.assistant और नीचे दिखा SHA-1. Firebase → Authentication → Sign-in method में Google चालू करें. " +
        "5-10 मिनट बाद फिर लॉगिन करें (फ़ोन में Google खाता जुड़ा हो, Play services अपडेट हों)"

    /** Play services का code 8 (INTERNAL_ERROR / "Unknown error") */
    fun isInternal(m: String): Boolean = Regex("^\\s*8\\s*:").containsMatchIn(m) || "INTERNAL_ERROR" in m

    /** इस APK की signing key का SHA-1 (Cloud Console के Android client के लिए) */
    fun signingSha1(c: Context): String? = try {
        val pm = c.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else @Suppress("DEPRECATION") pm.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.let { Hex.colon(java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray())) }
    } catch (_: Exception) { null }

    /** आम गलतियाँ साफ़ हिंदी में - सबसे आम: Cloud Console में Android client / SHA-1 नहीं */
    fun explain(e: Throwable): String {
        val m = (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        return when {
            "10:" in m || "DEVELOPER_ERROR" in m -> "Google सेटअप अधूरा (DEVELOPER_ERROR): Cloud Console में Android OAuth client - package com.dk.assistant और ऊपर दिखा SHA-1 - बनाइए"
            // 18 नवंबर 2026 (मालिक: "Google login failed: 8: [8] Unknown error"): Play services का INTERNAL_ERROR -
            // लगभग हमेशा Cloud Console में इस APK (package + SHA-1) का Android OAuth client नहीं / दूसरे project में
            isInternal(m) -> INTERNAL_HI
            "12501" in m || "canceled" in m.lowercase() -> "आपने Google स्क्रीन बंद कर दी"
            "7:" in m || "NETWORK" in m -> "इंटरनेट नहीं मिला"
            e is java.util.concurrent.TimeoutException -> "Google से जवाब नहीं आया (timeout)"
            else -> "Google login नहीं हुआ: " + m.take(160)
        }
    }
}
