package com.dk.assistant.behavior

import android.app.Application
import android.app.KeyguardManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.plus.ConfirmActivity
import com.dk.assistant.plus.ConfirmGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 16 नवंबर 2026 (ऑडिट P0-2): कार्ड पर उंगली से "हाँ" = YES, "ना" / बंद = NO, फ़ोन लॉक = कार्ड ही नहीं */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfirmCardBehaviorTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun button(v: View, t: String): Button? {
        if (v is Button && v.text.toString() == t) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) button(v.getChildAt(i), t)?.let { return it }
        return null
    }

    private fun press(label: String?): ConfirmGate.Answer = runBlocking {
        val d = async(Dispatchers.Default) { ConfirmGate.ask(app, "Termux में यह कमांड चलाऊँ?", "pkg install git") }
        var intent: android.content.Intent? = null
        val until = System.currentTimeMillis() + 5_000
        while (intent == null && System.currentTimeMillis() < until) { shadowOf(android.os.Looper.getMainLooper()).idle(); intent = shadowOf(app).nextStartedActivity; if (intent == null) Thread.sleep(10) }
        assertNotNull("कार्ड खुलना चाहिए", intent)
        val ctl = Robolectric.buildActivity(ConfirmActivity::class.java, intent).setup()
        if (label != null) button(ctl.get().window.decorView, label)!!.performClick() else ctl.pause().stop().destroy()
        d.await()
    }

    @Test fun yesButton_isYes() = assertEquals(ConfirmGate.Answer.YES, press("हाँ, करो"))
    @Test fun noButton_isNo() = assertEquals(ConfirmGate.Answer.NO, press("ना"))
    @Test fun closedCard_isNo() = assertEquals(ConfirmGate.Answer.NO, press(null))

    @Test fun lockedPhone_noCard() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertEquals(ConfirmGate.Answer.LOCKED, runBlocking { ConfirmGate.ask(app, "x", "y") })
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }
}
