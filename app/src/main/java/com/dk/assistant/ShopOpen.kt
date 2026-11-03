package com.dk.assistant

import android.content.Context
import com.dk.assistant.agent.core.ShopAsk
import org.json.JSONObject

/**
 * v3.156: Live का shop_search - "मुझे शर्ट देखनी है".
 * ऐप नहीं बताया + फ़ोन में कई shopping ऐप = पहले पूछो "किस ऐप पर दिखाऊँ?"; एक ही हो = सीधे उसी में.
 * ऐप बताया = उसी ऐप में वही चीज़ खोजो (सीधा search link; न चले तो ऐप खोलकर search box में लिखो).
 * ऑर्डर / भुगतान DK कभी नहीं करता.
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
        val wanted = args.optString("app").trim().takeIf { it.isNotBlank() && it.lowercase() != "auto" }
        val shop = wanted?.let { w -> ShopAsk.pick(w, have) ?: ShopAsk.shopIn(w) }
        if (shop == null) {
            when (have.size) {
                0 -> return "NO_SHOP_APP: No shopping app (Flipkart, Meesho, Amazon, Myntra, Ajio) is installed. Tell the user in one short Hindi line and offer to open it in the browser/Play Store."
                1 -> return open(svc, have[0], item)
                else -> {
                    val hi = args.optString("item_hindi").ifBlank { item }
                    return "ASK_USER: Several shopping apps are installed: ${have.joinToString { it.label }}. Do NOT open anything yet. " +
                        "Say exactly this one line and wait: \"${ShopAsk.question(hi, have)}\". When he answers, call shop_search again with app = the app he named and the same item."
                }
            }
        }
        if (have.none { it.key == shop.key }) return "NOT_INSTALLED: ${shop.label} is not installed on the phone. " +
            (if (have.isNotEmpty()) "Installed: ${have.joinToString { it.label }} - ask the user which one to use." else "Tell the user briefly.")
        return open(svc, shop, item)
    }

    private suspend fun open(svc: DKAccessibilityService, shop: ShopAsk.Shop, item: String): String {
        DKState.set(Mode.WORKING)
        val r = try { AppLauncher.shopSearch(svc, shop.key, item) } catch (e: Exception) { "ERROR: ${e.message}" }
        DKLog.d("Shop: ${shop.label} \"$item\" -> ${r.take(120)}")
        if (r.startsWith("ERROR")) return "ERROR: ${shop.label} नहीं खुला. Tell the user in one short Hindi line."
        if (!r.startsWith("opened")) {
            // सीधा link नहीं चला - ऐप खुला है, search box में लिखो (DK का phone agent)
            return "OPENED_APP_ONLY: ${shop.label} is open but its search did not open by link. Now call phone_task with request = " +
                "\"${shop.label} में search box पर टैप करके '$item' लिखो और search करो\". Do not place any order."
        }
        return "SHOWING: ${shop.label} is now showing search results for \"$item\". Tell the user in one short Hindi line " +
            "(e.g. \"${shop.label} पर दिखा दिया\") and ask if he wants a colour/size/price filter or to open one. Never buy or pay."
    }
}
