package com.dk.assistant

import android.content.Context
import com.dk.assistant.agent.core.ShopAsk
import com.dk.assistant.agent.master.ActionLog
import com.dk.assistant.agent.master.DkMaster
import com.dk.assistant.agent.master.ShopRules
import org.json.JSONObject

/**
 * v3.156: Live का shop_search - "मुझे शर्ट देखनी है".
 * ऐप नहीं बताया + फ़ोन में कई shopping ऐप = पहले पूछो "किस ऐप पर दिखाऊँ?"; एक ही हो = सीधे उसी में.
 * ऐप बताया = उसी ऐप में वही चीज़ खोजो (सीधा search link; न चले तो ऐप खोलकर search box में लिखो).
 * ऑर्डर / भुगतान DK कभी नहीं करता.
 * 25 नवंबर 2026 (शॉपिंग एजेंट): DK मास्टर चालू = बजट / साइज़ खोज में, नतीजे वाली स्क्रीन से सच में दिखे दाम पढ़कर AI को ([ShopRules]),
 * हर कदम मास्टर लॉग में. मास्टर बंद = बिल्कुल पहले जैसा.
 */
object ShopOpen {
    fun installed(c: Context): List<ShopAsk.Shop> =
        ShopAsk.installed { p -> try { c.packageManager.getLaunchIntentForPackage(p) != null } catch (_: Exception) { false } }

    suspend fun tool(svc: DKAccessibilityService, args: JSONObject): String {
        val said = args.optString("user_words").trim()
        val item = args.optString("item").ifBlank { args.optString("query") }.trim()
            .ifBlank { ShopAsk.item(said).orEmpty() }
        if (item.isBlank()) return "ASK_USER: The user did not say what to show. Ask in one short Hindi line: \"क्या दिखाऊँ - शर्ट, जींस, जूते या कुछ और?\""
        val have = installed(svc)
        val master = DkMaster.on(svc)
        val want = if (master) ShopRules.want("$said $item") else ShopRules.Want()
        val wanted = args.optString("app").trim().takeIf { it.isNotBlank() && it.lowercase() != "auto" }
        val shop = wanted?.let { w -> ShopAsk.pick(w, have) ?: ShopAsk.shopIn(w) }
        if (shop == null) {
            when (have.size) {
                // 25 नवंबर 2026 (मालिक: "ऐप नहीं है तो ब्राउज़र में वो काम चालू होना चाहिए") - पूछना नहीं, सीधे वेबसाइट पर वही खोज
                0 -> return web(svc, ShopAsk.SHOPS[0], item, want, master, why = "No shopping app is installed")
                1 -> return open(svc, have[0], item, want, master)
                else -> {
                    val hi = args.optString("item_hindi").ifBlank { item }
                    return "ASK_USER: Several shopping apps are installed: ${have.joinToString { it.label }}. Do NOT open anything yet. " +
                        "Say exactly this one line and wait: \"${ShopAsk.question(hi, have)}\". When he answers, call shop_search again with app = the app he named and the same item."
                }
            }
        }
        if (have.none { it.key == shop.key }) return web(svc, shop, item, want, master, why = "${shop.label} app is not installed")
        return open(svc, shop, item, want, master)
    }

    private suspend fun open(svc: DKAccessibilityService, shop: ShopAsk.Shop, item0: String, want: ShopRules.Want, master: Boolean): String {
        DKState.set(Mode.WORKING)
        val item = if (master) ShopRules.query(item0, want) else item0
        if (master) ActionLog.add(svc, "शॉपिंग", "${shop.label} में खोज: $item")
        val prev = if (master) svc.currentPackage() else ""
        val r = try { AppLauncher.shopSearch(svc, shop.key, item) } catch (e: Exception) { "ERROR: ${e.message}" }
        DKLog.d("Shop: ${shop.label} \"$item\" -> ${r.take(120)}")
        if (r.startsWith("ERROR")) return "ERROR: ${shop.label} नहीं खुला. Tell the user in one short Hindi line."
        if (!r.startsWith("opened")) {
            // सीधा link नहीं चला - ऐप खुला है, search box में लिखो (DK का phone agent)
            return "OPENED_APP_ONLY: ${shop.label} is open but its search did not open by link. Now call phone_task with request = " +
                "\"${shop.label} में search box पर टैप करके '$item' लिखो और search करो\". Do not place any order."
        }
        shop.pkgs.firstOrNull { p -> try { svc.packageManager.getLaunchIntentForPackage(p) != null } catch (_: Exception) { false } }
            ?.let { ShowPage.app(svc, it) }
        val base = "SHOWING: ${shop.label} is now showing search results for \"$item\". Tell the user in one short Hindi line " +
            "(e.g. \"${shop.label} पर दिखा दिया\") and ask if he wants a colour/size/price filter or to open one. Never buy or pay."
        if (!master) return base
        return base + prices(svc, want, prev, prev !in shop.pkgs) + "\n" + ShopRules.AGENT_NOTE
    }

    /** ऐप नहीं = उसी दुकान की वेबसाइट पर वही खोज, फ़ोन के अपने ब्राउज़र में (मालिक के सामने; DK का phone agent वहीं आगे) */
    private suspend fun web(svc: DKAccessibilityService, shop: ShopAsk.Shop, item0: String, want: ShopRules.Want, master: Boolean, why: String): String {
        val item = if (master) ShopRules.query(item0, want) else item0
        val url = com.dk.assistant.agent.master.ShowRules.shopWeb(shop.key, item)
            ?: return "NOT_INSTALLED: $why. Tell the user in one short Hindi line."
        DKState.set(Mode.WORKING)
        val prev = svc.currentPackage()
        val ok = try {
            svc.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { Quiet.note(e); false }
        DKLog.d("Shop (वेबसाइट): ${shop.label} \"$item\" -> ${if (ok) "खुला" else "नहीं खुला"}")
        if (!ok) return "ERROR: $why and the browser did not open. Tell the user in one short Hindi line."
        ShowPage.web(url, "${shop.label}: $item")
        if (master) ActionLog.add(svc, "शॉपिंग", "${shop.label} वेबसाइट (ऐप नहीं) में खोज: $item")
        val base = "SHOWING_WEB: $why, so DK opened ${shop.label}'s website in the phone's browser with search results for \"$item\". " +
            "Tell the user in one short Hindi line (e.g. \"ऐप नहीं है, इसलिए ${shop.label} की वेबसाइट पर दिखा दिया\") and ask if he wants a filter or to open one. " +
            "Continue on this browser page with phone_task if he asks. Never buy or pay."
        if (!master) return base
        return base + prices(svc, want, prev, true) + "\n" + ShopRules.AGENT_NOTE
    }

    /** मास्टर: नतीजे वाली स्क्रीन से सच में दिखे दाम (न दिखे = कुछ नहीं जोड़ते, AI अंदाज़ा न लगाए) */
    private suspend fun prices(svc: DKAccessibilityService, want: ShopRules.Want, prev: String, newApp: Boolean): String = try {
        svc.waitSettle(prev, newApp = newApp, maxMs = 5000L)
        val list = ShopRules.offers(svc.readScreen().text)
        ActionLog.add(svc, "दाम", ShopRules.priceHi(list, want))
        ShopRules.priceNote(list, want)
    } catch (e: Exception) {
        Quiet.note(e)
        ""
    }
}
