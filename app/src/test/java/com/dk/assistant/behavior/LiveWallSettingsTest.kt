package com.dk.assistant.behavior

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.SettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 22 नवंबर 2026: सेटिंग › DK लाइव › लाइव वॉलपेपर सच में खुलता है, और DK लाइव मेन्यू में दिखता है */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveWallSettingsTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    private fun open(section: String) = Robolectric.buildActivity(SettingsActivity::class.java,
        Intent(c, SettingsActivity::class.java).putExtra("section", section)).setup()

    @Test fun dkLiveMenuShowsLiveWallpaper() {
        val s = open("cat_live")
        assertTrue(Ui.hasText(s.get(), "लाइव वॉलपेपर"))
        s.pause().stop().destroy()
    }

    @Test fun liveWallpaperSectionOpens() {
        val s = open("livewall")
        val a = s.get()
        assertTrue(Ui.hasText(a, "देखें और लगाएँ"))
        assertTrue(Ui.hasText(a, "कहाँ लगाना है"))
        assertTrue(Ui.hasText(a, "होम"))
        s.pause().stop().destroy()
    }
}
