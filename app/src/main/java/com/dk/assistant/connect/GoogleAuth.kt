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
        Res.Fail(explain(e))
    }

    fun read(r: AuthorizationResult): Res = when {
        r.hasResolution() -> Res.NeedsUi(r.pendingIntent)
        !r.accessToken.isNullOrBlank() -> Res.Token(r.accessToken!!)
        else -> Res.Fail("Google ने token नहीं दिया")
    }

    /** आम गलतियाँ साफ़ हिंदी में - सबसे आम: Cloud Console में Android client / SHA-1 नहीं */
    fun explain(e: Throwable): String {
        val m = (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        return when {
            "10:" in m || "DEVELOPER_ERROR" in m -> "Google सेटअप अधूरा (DEVELOPER_ERROR): Cloud Console में Android OAuth client - package com.dk.assistant और ऊपर दिखा SHA-1 - बनाइए"
            "12501" in m || "canceled" in m.lowercase() -> "आपने Google स्क्रीन बंद कर दी"
            "7:" in m || "NETWORK" in m -> "इंटरनेट नहीं मिला"
            e is java.util.concurrent.TimeoutException -> "Google से जवाब नहीं आया (timeout)"
            else -> "Google login नहीं हुआ: " + m.take(160)
        }
    }
}
