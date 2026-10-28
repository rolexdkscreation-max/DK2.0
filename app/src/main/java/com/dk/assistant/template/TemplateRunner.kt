package com.dk.assistant.template

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.agent.android.SocialAgent
import com.dk.assistant.agent.social.TaskStatus
import com.dk.assistant.auto.AutoPrefs
import com.dk.assistant.auto.AutoSafety
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** साँचों की सेटिंग: अंदर वाले साँचों का चालू/बंद + अपने बनाए साँचे. अलग फ़ाइल "dk_templates". */
object TemplateStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_templates", Context.MODE_PRIVATE)

    fun on(c: Context, id: String) = sp(c).getBoolean("on_$id", true)
    fun setOn(c: Context, id: String, v: Boolean) = sp(c).edit().putBoolean("on_$id", v).apply()

    fun user(c: Context): List<TemplateCommands.Template> = try {
        val a = JSONArray(sp(c).getString("user", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val simple = o.optString("pattern")
            TemplateCommands.Template(o.optString("id"), o.optString("name"), simple, listOf(TemplateCommands.userPattern(simple)),
                o.optString("slots").split(',').map { it.trim() }.filter { it.isNotEmpty() }, o.optString("action"), o.optString("app"),
                builtin = false, on = o.optBoolean("on", true))
        }
    } catch (_: Exception) { emptyList() }

    fun saveUser(c: Context, list: List<TemplateCommands.Template>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("pattern", it.example).put("slots", it.slots.joinToString(","))
            .put("action", it.action).put("app", it.app).put("on", it.on)) }
        sp(c).edit().putString("user", a.toString()).apply()
    }

    /** मिलान का क्रम: अपने साँचे पहले, फिर अंदर वाले (हर एक अपने switch के साथ) */
    fun all(c: Context): List<TemplateCommands.Template> =
        user(c) + TemplateCommands.BUILTIN.map { it.copy(on = on(c, it.id)) }

    /** अपने साँचे में चुने जा सकने वाले काम */
    val USER_ACTIONS = linkedMapOf(
        "open_app" to "ऐप खोलो",
        "app_search" to "ऐप में पहली खाली जगह वाली चीज़ सर्च करो",
        "wa_send" to "WhatsApp: पहली जगह = नाम, दूसरी = मैसेज",
        "call" to "पहली जगह वाले को कॉल करो",
        "yt_play" to "YouTube पर पहली जगह वाली चीज़ चलाओ"
    )
}

/**
 * साँचे का काम असल में करना (Android intents + Accessibility). AI / API key नहीं लगती.
 * लौटता है: छोटी बोलने लायक रिपोर्ट.
 */
object TemplateRunner {
    @Volatile private var lastShop = ""

    suspend fun run(svc: DKAccessibilityService, m: TemplateCommands.Match, ask: suspend (String) -> String?): String {
        DKLog.d("साँचा: ${m.t.id}")
        val a = m["a"]; val b = m["b"]
        return when (m.t.action) {
            "wa_send" -> waSend(svc, a, b, ask)
            "open_app" -> plain(svc.perform(act("open_app", "name" to m.t.app)), "${label(svc, m.t.app)} खोल दिया।")
            "call" -> call(svc, a)
            "app_search" -> plain(svc.perform(act("app_search", "app" to m.t.app, "query" to a)), "${label(svc, m.t.app)} में \"$a\" सर्च कर दिया।")
            "yt_play" -> plain(svc.perform(act("youtube_play", "query" to a)), "YouTube पर \"$a\" चला रही हूँ।")
            "yt_search" -> plain(svc.perform(act("youtube_search", "query" to a)), "YouTube पर \"$a\" सर्च कर दिया।")
            "navigate" -> plain(svc.perform(act("navigate", "to" to a)), "$a का रास्ता शुरू कर दिया।")
            "distance" -> svc.perform(act("distance", "to" to a)).let { r -> if (bad(r)) "दूरी पता नहीं चल पाई: ${tail(r)}" else r }
            "alarm" -> alarm(svc, a)
            "timer" -> timer(svc, a)
            "shop_search" -> shopSearch(svc, m["app"], m["q"])
            "cart_add" -> cartAdd(svc, a)
            "cart_open" -> cartOpen(svc, m["app"])
            "scroll_down" -> plain(svc.perform(act("scroll", "direction" to "down")), "नीचे कर दिया।")
            "scroll_up" -> plain(svc.perform(act("scroll", "direction" to "up")), "ऊपर कर दिया।")
            "back" -> plain(svc.perform(act("back")), "पीछे आ गई।")
            "home" -> plain(svc.perform(act("home")), "होम स्क्रीन पर आ गई।")
            "vol_up" -> plain(svc.perform(act("volume", "direction" to "up")), "आवाज़ बढ़ा दी।")
            "vol_down" -> plain(svc.perform(act("volume", "direction" to "down")), "आवाज़ कम कर दी।")
            "vol_set" -> { val n = TemplateTime.number(a) ?: 50
                plain(svc.perform(JSONObject().put("type", "volume").put("direction", "set").put("level", n)), "आवाज़ $n प्रतिशत कर दी।") }
            "call_answer" -> plain(svc.perform(JSONObject().put("type", "answer_call").put("speaker", true)), "कॉल उठा ली और स्पीकर चालू कर दिया।")
            "call_end" -> plain(svc.perform(act("end_call")), "कॉल काट दी।")
            else -> "यह साँचा अभी चल नहीं पाया (काम \"${m.t.action}\" पहचाना नहीं)।"
        }
    }

    private fun act(type: String, vararg kv: Pair<String, String>) = JSONObject().put("type", type).also { o -> kv.forEach { o.put(it.first, it.second) } }
    private fun bad(r: String) = r.startsWith("ERROR") || r.startsWith("STEP_FAILED") || r.startsWith("ASK") || r.contains("ERROR_")
    private fun tail(r: String) = r.substringAfter(":", r).trim().take(160)
    private fun plain(r: String, ok: String) = if (bad(r)) (if (r.startsWith("ASK")) tail(r) else "नहीं हो पाया: ${tail(r)}") else ok
    private fun label(c: Context, app: String) = when (app.lowercase()) {
        "whatsapp" -> "WhatsApp"; "instagram" -> "Instagram"; "facebook" -> "Facebook"; else -> app.replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------ कॉल
    private suspend fun call(svc: DKAccessibilityService, name: String): String {
        val r = svc.perform(act("call_contact", "name" to name))
        return when {
            r.startsWith("ASK") -> tail(r)       // कई नाम मिले = पूछो, अंदाज़ा नहीं
            bad(r) -> "कॉल नहीं लग पाई: ${tail(r)}"
            else -> "$name को कॉल लगा रही हूँ।"
        }
    }

    // ------------------------------------------------------------------ WhatsApp मैसेज
    private suspend fun waSend(svc: DKAccessibilityService, name: String, msg: String, ask: suspend (String) -> String?): String {
        val args = JSONObject().put("platform", "whatsapp").put("action", "send_message").put("contact", name).put("message", msg)
        val st = try { JSONObject(SocialAgent.start(svc, args, ownConfirm = true)) } catch (e: Exception) { return "WhatsApp का काम शुरू नहीं हो पाया।" }
        if (st.optString("status") != "started") {
            val why = st.optString("message").ifBlank { st.optString("error") }
            return if (st.optString("error").startsWith("contact")) "$why पूरा नाम बोलकर फिर से कहिए।" else "मैसेज नहीं भेज पाई: $why"
        }
        val confirm = AutoPrefs.confirm(svc)
        val t0 = System.currentTimeMillis()
        var asked = false
        while (System.currentTimeMillis() - t0 < 90_000) {
            delay(400)
            val rep = SocialAgent.lastReport ?: continue
            if (rep.status == TaskStatus.WAITING_CONFIRMATION && !asked) {
                asked = true
                if (confirm) {
                    val ans = ask("$name को \"$msg\" भेज दूँ? हाँ या नहीं बोलिए।")
                    if (!AutoSafety.yes(ans)) { SocialAgent.control(JSONObject().put("command", "cancel")); return "ठीक है, मैसेज नहीं भेजा।" }
                }
                if (!SocialAgent.confirmByDk()) return "मैसेज भेजने की पुष्टि नहीं हो पाई — कुछ नहीं भेजा।"
                continue
            }
            if (rep.status == TaskStatus.WAITING_USER) return rep.message.ifBlank { "WhatsApp में आगे बढ़ने के लिए आपकी ज़रूरत है।" }
            if (rep.status.finished) return when (rep.status) {
                TaskStatus.COMPLETED -> "$name को \"$msg\" भेज दिया।"
                TaskStatus.CANCELLED -> "मैसेज नहीं भेजा (काम रोक दिया)।"
                else -> rep.message.ifBlank { "मैसेज नहीं भेज पाई।" }
            }
        }
        return "WhatsApp बहुत देर तक जवाब नहीं दे रहा — मैं रुक गई, पक्का नहीं कि मैसेज गया।"
    }

    // ------------------------------------------------------------------ अलार्म / टाइमर
    private fun alarm(svc: DKAccessibilityService, spoken: String): String {
        val now = Calendar.getInstance()
        val c = TemplateTime.parseClock(spoken, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)) ?: return "समय समझ नहीं आया — जैसे \"सुबह 7 बजे का अलार्म लगाओ\" बोलिए।"
        return try {
            svc.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, c.hour).putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "DK").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "${c.spoken()} का अलार्म लगा दिया।"
        } catch (e: Exception) { "अलार्म नहीं लग पाया — फ़ोन में घड़ी (Clock) ऐप नहीं मिला।" }
    }

    private fun timer(svc: DKAccessibilityService, spoken: String): String {
        val min = TemplateTime.minutes(spoken) ?: return "कितने मिनट, समझ नहीं आया।"
        return try {
            svc.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, min * 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "DK").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$min मिनट का टाइमर लगा दिया।"
        } catch (e: Exception) { "टाइमर नहीं लग पाया — फ़ोन में घड़ी (Clock) ऐप नहीं मिला।" }
    }

    // ------------------------------------------------------------------ शॉपिंग (कार्ट तक; ऑर्डर / भुगतान / पता नहीं)
    private val URL_SHOPS = setOf("flipkart", "meesho", "amazon", "blinkit", "zepto")
    private const val STOP_NOTE = " ऑर्डर पक्का करना, भुगतान या पता बदलना मैं नहीं करूँगी — आगे आप बताइए।"

    private suspend fun shopSearch(svc: DKAccessibilityService, app: String, q: String): String {
        lastShop = app
        val r = if (app in URL_SHOPS) svc.perform(act("shop_search", "app" to app, "query" to q))
            else svc.perform(act("app_search", "app" to app, "query" to q))
        return plain(r, "${label(svc, app)} पर \"$q\" सर्च कर दिया।")
    }

    /** स्क्रीन की पंक्ति "[12] Button "ADD" (desc) {click} @x,y" -> id, दिखने वाला शब्द, desc */
    private data class El(val id: Int, val text: String, val desc: String, val click: Boolean) {
        val label get() = "$text $desc".trim()
    }
    private fun elements(screen: String): List<El> = screen.lineSequence().mapNotNull { line ->
        val m = Regex("^\\[(\\d+)] \\S+(?: \"([^\"]*)\")?(?: \\(([^)]*)\\))?(?: \\{([^}]*)})?").find(line) ?: return@mapNotNull null
        El(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3], m.groupValues[4].contains("click"))
    }.toList()

    private val ADD_EXACT = setOf("add", "add +", "+ add", "ऐड", "जोड़ें", "जोड़ें", "add item")
    private val ADD_TEXT = Regex("(?i)(add to cart|add to bag|add to basket|add to trolley|कार्ट में जोड़ें|कार्ट में डालें)")
    private fun isAdd(e: El) = !NEVER.containsMatchIn(e.label) &&
        (e.text.trim().lowercase() in ADD_EXACT || e.desc.trim().lowercase() in ADD_EXACT || ADD_TEXT.containsMatchIn(e.label))
    private val NEVER = Regex("(?i)(buy now|place order|proceed to pay|pay now|checkout|अभी खरीदें|ऑर्डर करें|भुगतान)")

    private suspend fun cartAdd(svc: DKAccessibilityService, item: String): String {
        if (item.isNotBlank()) {
            val shop = lastShop.ifBlank { shopOfPkg(svc.currentPackage()) }
            if (shop.isBlank()) return "किस ऐप में? पहले बोलिए, जैसे \"ब्लिंकिट पर $item सर्च करो\"।"
            val s = shopSearch(svc, shop, item)
            if (s.startsWith("नहीं हो पाया")) return s
            delay(3500)
        }
        repeat(2) { attempt ->
            val sc = svc.readScreen()
            AutoSafety.blockReason(sc.text, sc.packageName, svc.hasPasswordField())?.let { return it }
            val els = elements(sc.text)
            val add = els.firstOrNull { isAdd(it) }
            if (add != null) {
                val r = svc.perform(JSONObject().put("type", "click").put("id", add.id))
                if (bad(r)) return "\"Add to cart\" दबा नहीं पाई: ${tail(r)}"
                delay(1200)
                return "कार्ट में जोड़ दिया।$STOP_NOTE"
            }
            // नतीजों की सूची: पहला मिलता-जुलता प्रोडक्ट खोलो, फिर Add to cart
            if (attempt == 0 && item.isNotBlank()) {
                val w = item.split(' ').filter { it.length >= 2 }.take(2)
                val first = els.firstOrNull { e -> e.click && w.any { e.label.contains(it, ignoreCase = true) } && !e.label.contains("search", true) && !NEVER.containsMatchIn(e.label) }
                    ?: return "स्क्रीन पर \"$item\" वाला प्रोडक्ट नहीं मिला।"
                svc.perform(JSONObject().put("type", "click").put("id", first.id)); delay(2500)
            } else if (attempt == 0) return "स्क्रीन पर \"Add to cart\" बटन नहीं मिला — प्रोडक्ट खोलकर फिर कहिए \"इसे कार्ट में जोड़ो\"।"
        }
        return "\"Add to cart\" बटन नहीं मिला — मैं रुक गई।"
    }

    private suspend fun cartOpen(svc: DKAccessibilityService, app0: String): String {
        val app = app0.ifBlank { lastShop.ifBlank { shopOfPkg(svc.currentPackage()) } }
        if (app.isBlank()) return "किस ऐप का कार्ट? जैसे \"फ्लिपकार्ट का कार्ट खोलो\" बोलिए।"
        if (shopOfPkg(svc.currentPackage()) != app) {
            val r = svc.perform(act("open_app", "name" to app)); if (bad(r)) return "नहीं हो पाया: ${tail(r)}"; delay(3000)
        }
        val sc = svc.readScreen()
        val cart = elements(sc.text).firstOrNull { it.click && Regex("(?i)(cart|कार्ट|basket|बास्केट|\\bbag\\b)").containsMatchIn(it.label) && !NEVER.containsMatchIn(it.label) }
            ?: return "${label(svc, app)} में कार्ट का बटन नहीं मिला।"
        val r = svc.perform(JSONObject().put("type", "click").put("id", cart.id))
        lastShop = app
        return if (bad(r)) "कार्ट नहीं खुल पाया: ${tail(r)}" else "कार्ट खोल दिया।$STOP_NOTE"
    }

    private fun shopOfPkg(pkg: String): String = when {
        pkg.startsWith("com.flipkart") -> "flipkart"
        pkg.startsWith("com.meesho") -> "meesho"
        pkg.startsWith("in.amazon") || pkg.startsWith("com.amazon.mShop") -> "amazon"
        pkg.startsWith("com.grofers") -> "blinkit"
        pkg.startsWith("com.zeptoconsumerapp") -> "zepto"
        pkg == "in.swiggy.android.instamart" -> "instamart"
        pkg.startsWith("in.swiggy") -> "swiggy"
        pkg.startsWith("com.application.zomato") -> "zomato"
        else -> ""
    }
}
