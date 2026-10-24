package com.dk.assistant.license

import com.dk.assistant.AssistantBrand
import org.json.JSONObject

/** DK AI Part 13: access key की हालत */
enum class KeyStatus {
    /** मालिक ने backend सेट नहीं किया (dk_license.json नहीं) -> ऐप पहले जैसा पूरा चलता है */
    NOT_CONFIGURED,
    NO_KEY, ACTIVE, EXPIRED, BLOCKED, INACTIVE;

    /** इस हालत में DK काम करे? */
    val allowed: Boolean get() = this == NOT_CONFIGURED || this == ACTIVE
}

data class LicenseInfo(
    val active: Boolean,
    val plan: String,
    val expiresAt: Long,
    val statusText: String,
    val blocked: Boolean,
    val hasRememberedKey: Boolean,
    val status: KeyStatus,
)

/** backend का जवाब (parse किया हुआ) */
data class RemoteResult(
    val ok: Boolean,
    val active: Boolean,
    val plan: String,
    val expiresAt: Long,
    val blocked: Boolean,
    val message: String,
)

/**
 * pure नियम - Android import नहीं, JVM test.
 * expiresAt <= 0 या plan "lifetime" = कभी खत्म नहीं.
 */
object AccessKeyRules {
    const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
    private val KEY_OK = Regex("^[A-Z0-9][A-Z0-9-]{6,62}[A-Z0-9]$")

    fun admin(): String = AssistantBrand.OWNER_NAME

    fun isLifetime(plan: String, expiresAt: Long): Boolean = expiresAt <= 0L || plan.trim().equals("lifetime", ignoreCase = true)

    fun decide(configured: Boolean, key: String?, plan: String, expiresAt: Long, activeFlag: Boolean, blocked: Boolean, now: Long): KeyStatus = when {
        !configured -> KeyStatus.NOT_CONFIGURED
        key.isNullOrBlank() -> KeyStatus.NO_KEY
        blocked -> KeyStatus.BLOCKED                              // एडमिन का block सबसे ऊपर
        !activeFlag -> KeyStatus.INACTIVE
        !isLifetime(plan, expiresAt) && now >= expiresAt -> KeyStatus.EXPIRED
        else -> KeyStatus.ACTIVE
    }

    fun info(configured: Boolean, key: String?, plan: String, expiresAt: Long, activeFlag: Boolean, blocked: Boolean, now: Long, en: Boolean = false): LicenseInfo {
        val st = decide(configured, key, plan, expiresAt, activeFlag, blocked, now)
        return LicenseInfo(st.allowed, plan, expiresAt, statusText(st, plan, expiresAt, now, en), blocked, !key.isNullOrBlank(), st)
    }

    fun daysLeft(expiresAt: Long, now: Long): Long = ((expiresAt - now).coerceAtLeast(0L) + 86_399_999L) / 86_400_000L

    fun statusText(st: KeyStatus, plan: String, expiresAt: Long, now: Long, en: Boolean = false): String = when (st) {
        KeyStatus.NOT_CONFIGURED -> if (en) "Licensing not configured - DK works fully" else "लाइसेंस सेट नहीं है (licensing not configured) - DK पूरा चलता है"
        KeyStatus.NO_KEY -> if (en) "No access key. Get one from admin ${admin()}" else "कोई access key नहीं है। key के लिए एडमिन ${admin()} से संपर्क करें"
        KeyStatus.BLOCKED -> if (en) "Access key ${AssistantBrand.blockedByAdmin()}" else "Access key को एडमिन ${admin()} ने रोक दिया है"
        KeyStatus.INACTIVE -> if (en) "Access key inactive, please contact admin ${admin()}" else "Access key चालू नहीं है, कृपया एडमिन ${admin()} से संपर्क करें"
        KeyStatus.EXPIRED -> if (en) "Access key expired, please contact admin ${admin()}" else "Access key की अवधि खत्म हो गई, कृपया एडमिन ${admin()} से संपर्क करें"
        KeyStatus.ACTIVE -> {
            val p = plan.ifBlank { "standard" }
            if (isLifetime(plan, expiresAt)) (if (en) "Active • $p • no expiry" else "चालू • $p • कोई अवधि नहीं")
            else daysLeft(expiresAt, now).let { d -> if (en) "Active • $p • $d days left" else "चालू • $p • $d दिन बाकी" }
        }
    }

    /** DK लाइव / कमांड रोकते समय बोला जाने वाला साफ़ संदेश (मॉडल भी यही बोले) */
    fun refusal(st: KeyStatus, en: Boolean = false): String = when (st) {
        KeyStatus.BLOCKED -> if (en) "Access key ${AssistantBrand.blockedByAdmin()}. Please contact admin ${admin()}." else "आपकी access key एडमिन ${admin()} ने रोक दी है। कृपया उनसे संपर्क करें।"
        KeyStatus.EXPIRED -> if (en) "Access key expired, please contact admin ${admin()}." else "आपकी access key की अवधि खत्म हो गई है, कृपया एडमिन ${admin()} से संपर्क करें।"
        else -> if (en) "Access key inactive, please contact admin ${admin()}." else "Access key चालू नहीं है, कृपया एडमिन ${admin()} से संपर्क करें।"
    }

    /** उपयोगकर्ता की डाली key: बड़े अक्षर, खाली जगह हटाकर; गलत रूप = null */
    fun normalizeKey(raw: String?): String? {
        val k = raw.orEmpty().trim().uppercase().replace(Regex("\\s+"), "")
        return k.takeIf { KEY_OK.matches(it) }
    }

    /** liveCheck ज़रूरी? (3 घंटे में एक बार; force = activate के तुरंत बाद आदि) */
    fun shouldLiveCheck(lastCheck: Long, now: Long, force: Boolean = false): Boolean =
        force || lastCheck <= 0L || now - lastCheck >= CHECK_INTERVAL_MS || now < lastCheck

    /** backend जवाब: {"ok":true,"active":true,"plan":"monthly","expires_at":1767225600000,"blocked":false,"message":""} */
    fun parseRemote(body: String?): RemoteResult? {
        val o = try { JSONObject(body ?: return null) } catch (_: Exception) { return null }
        if (!o.has("ok") && !o.has("active") && !o.has("blocked")) return null
        val blocked = o.optBoolean("blocked", false)
        return RemoteResult(
            ok = o.optBoolean("ok", true),
            active = o.optBoolean("active", false) && !blocked,
            plan = o.optString("plan", ""),
            expiresAt = o.optLong("expires_at", 0L),
            blocked = blocked,
            message = o.optString("message", "").take(200),
        )
    }

    /** dk_license.json: {"endpoint":"https://..."} - सिर्फ़ https मान्य */
    fun parseConfig(json: String?): String? {
        val o = try { JSONObject(json ?: return null) } catch (_: Exception) { return null }
        val e = o.optString("endpoint", "").trim()
        return e.takeIf { it.startsWith("https://") && it.length > 12 && !it.contains(' ') }
    }

    /** device id को सीधे नहीं भेजते - hash (app का नाम मिलाकर) */
    fun deviceHash(rawId: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("dk-ai:$rawId".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
