package com.dk.assistant

import com.dk.assistant.AlarmOff.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmOffTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun p(t: String) = AlarmOff.parse(t, 10, 0)

    @Test fun stopRingingOrNext() {
        for (t in listOf("अलार्म बंद करो", "डीके अलार्म बंद कर दो", "आलम बंद करो", "alarm off karo", "stop the alarm", "अलार्म स्टॉप करो", "अलार्म हटाओ", "अलार्म डिसमिस करो"))
            assertEquals(t, AlarmOff.Cmd(false, Mode.NEXT), p(t))
    }

    @Test fun allAlarms() {
        assertEquals(Mode.ALL, p("सारे अलार्म बंद करो")!!.mode)
        assertEquals(Mode.ALL, p("सभी अलार्म हटा दो")!!.mode)
        assertEquals(Mode.ALL, p("turn off all alarms")!!.mode)
    }

    @Test fun byTime() {
        val c = p("सुबह 7 बजे का अलार्म बंद करो")!!
        assertEquals(Mode.TIME, c.mode); assertEquals(7, c.hour); assertEquals(0, c.minute)
        val d = p("साढ़े छह बजे वाला अलार्म हटाओ")!!
        assertEquals(Mode.TIME, d.mode); assertEquals(30, d.minute)
    }

    @Test fun snooze() {
        assertTrue(p("अलार्म स्नूज़ करो")!!.snooze)
        assertTrue(p("snooze the alarm")!!.snooze)
    }

    @Test fun notOff_noMatch() {
        assertNull(p("सुबह 7 बजे का अलार्म लगाओ"))
        assertNull(p("अलार्म बंद मत करो"))
        assertNull(p("आलम क्या है"))
        assertNull(p("टॉर्च बंद करो"))
    }

    @Test fun router_sendsToAlarmOff_notAi() {
        val r = LocalRouter.route("डीके अलार्म बंद करो", wake)!!
        assertEquals("alarm_off", r.action.optString("type"))
        assertEquals(AlarmOff.Cmd(false, Mode.NEXT), AlarmOff.fromJson(r.action))
        assertEquals("ALL", LocalRouter.route("सारे अलार्म बंद करो", wake)!!.action.optString("mode"))
    }

    @Test fun notificationButton() {
        assertEquals(1, AlarmOff.pick(listOf("Snooze", "Dismiss"), false))
        assertEquals(0, AlarmOff.pick(listOf("Snooze", "Dismiss"), true))
        assertEquals(1, AlarmOff.pick(listOf("स्नूज़ करें", "ख़ारिज करें"), false))
        assertEquals(0, AlarmOff.pick(listOf("Stop"), false))
        assertEquals(-1, AlarmOff.pick(listOf("Open"), false))
        assertTrue(AlarmOff.isClockPkg("com.google.android.deskclock"))
        assertTrue(AlarmOff.isClockPkg("com.sec.android.app.clockpackage"))
        assertTrue(!AlarmOff.isClockPkg("com.whatsapp"))
    }
}
