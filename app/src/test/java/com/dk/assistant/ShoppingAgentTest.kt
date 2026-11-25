package com.dk.assistant

import com.dk.assistant.agent.master.MasterRules
import com.dk.assistant.agent.master.ShopRules
import com.dk.assistant.agent.master.ShopRules.Tap
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 3 - शॉपिंग एजेंट) */
class ShoppingAgentTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun budget_fromHindiAndEnglish() {
        assertEquals(500, ShopRules.want("500 रुपये के अंदर काली शर्ट दिखाओ").maxPrice)
        assertEquals(1500, ShopRules.want("under ₹1,500 shoes").maxPrice)
        assertEquals(800, ShopRules.want("800 तक का जूता").maxPrice)
        val r = ShopRules.want("1000 से 2000 के बीच घड़ी")
        assertEquals(1000, r.minPrice)
        assertEquals(2000, r.maxPrice)
        // दाम नहीं - "2 दिन के अंदर"
        assertNull(ShopRules.want("2 दिन के अंदर चाहिए").maxPrice)
        assertFalse(ShopRules.want("शर्ट दिखाओ").any)
    }

    @Test fun size_andCheapest() {
        assertEquals("M", ShopRules.want("साइज़ M की टी-शर्ट").size)
        assertEquals("XL", ShopRules.want("size xl hoodie").size)
        assertEquals("9", ShopRules.want("9 नंबर का जूता चाहिए").size)
        assertTrue(ShopRules.want("सबसे सस्ता ईयरबड दिखाओ").cheapest)
    }

    @Test fun query_addsSizeAndBudget() {
        assertEquals("black shirt for men size M under 500", ShopRules.query("black shirt for men", ShopRules.Want(maxPrice = 500, size = "M")))
        assertEquals("shoes", ShopRules.query("shoes", ShopRules.Want()))
    }

    private val SCREEN = """
        [1] Text "Roadster Men Black Shirt" @100,200
        [2] Text "₹499" @100,250
        [3] Text "₹1,299" @150,250
        [4] Text "61% off" @200,250
        [5] Text "HIGHLANDER Slim Fit Shirt" @100,400
        [6] Text "₹649" @100,450
        [7] Text "Free delivery" @100,470
    """.trimIndent()

    @Test fun offers_onlyWhatIsOnScreen() {
        val o = ShopRules.offers(SCREEN)
        assertEquals(2, o.size)
        assertEquals("Roadster Men Black Shirt", o[0].title)
        assertEquals(499, o[0].price)          // पहला दाम = बिक्री वाला, कटा हुआ ₹1,299 नहीं
        assertEquals(649, o[1].price)
        val w = ShopRules.Want(maxPrice = 500)
        assertEquals(listOf(499), ShopRules.fit(o, w).map { it.price })
        assertTrue(ShopRules.priceHi(o, w).contains("₹499"))
        assertTrue(ShopRules.priceNote(o, w).contains("never guess a price"))
        assertEquals("", ShopRules.priceNote(emptyList(), w))
        assertEquals("₹1,299", ShopRules.rupee(1299))
    }

    @Test fun buttons_cartAsk_buyAsk_payNever() {
        assertEquals(Tap.CART, ShopRules.tap("Button \"Add to cart\" (Add to cart)"))
        assertEquals(Tap.CART, ShopRules.tap("ADD TO BAG"))
        assertEquals(Tap.BUY, ShopRules.tap("Buy Now"))
        assertEquals(Tap.BUY, ShopRules.tap("Place Order"))
        assertEquals(Tap.BUY, ShopRules.tap("Proceed to Checkout"))
        assertEquals(Tap.PAY, ShopRules.tap("Pay ₹499"))
        assertEquals(Tap.PAY, ShopRules.tap("Proceed to Pay"))
        assertEquals(Tap.PAY, ShopRules.tap("Cash on Delivery"))
        assertEquals(Tap.PAY, ShopRules.tap("UPI ID डालें"))
        assertNull(ShopRules.tap("Search"))
        assertNull(ShopRules.tap("Filter"))
    }

    @Test fun cart_ownerAsked() {
        assertTrue(ShopRules.cartAsked("यह शर्ट कार्ट में डाल दो"))
        assertTrue(ShopRules.cartAsked("add to cart kar do"))
        assertFalse(ShopRules.cartAsked("काली शर्ट दिखाओ"))
    }

    @Test fun masterPlan_shoppingRules() {
        val p = MasterRules.plan("Amazon से फोन खरीदो")
        assertEquals(MasterRules.Specialist.SHOPPING, p.specialist)
        assertTrue(p.needsConfirm)
        val n = MasterRules.agentNote(p)
        assertTrue(n.contains("stop before checkout"))
        assertTrue(n.contains("never tap Pay"))
        assertTrue(n.contains("never guess or invent a price"))
    }

    @Test fun wiring_onlyWhenMasterOn() {
        val runner = src("AgentRunner.kt")
        assertTrue(runner.contains("ShopRules.tap(l)"))
        assertTrue(runner.contains("ShopRules.PAY_HANDOVER"))
        assertTrue(runner.contains("ShopRules.cartAsked(goal)"))
        assertTrue(runner.contains("master.DkMaster.on(svc)"))
        val shop = src("ShopOpen.kt")
        assertTrue(shop.contains("DkMaster.on(svc)"))
        assertTrue(shop.contains("if (!master) return base"))
        // पुराना जवाब वैसे ही
        assertTrue(shop.contains("Never buy or pay."))
        assertTrue(src("SettingsBrain.kt").contains("शॉपिंग एजेंट"))
    }
}
