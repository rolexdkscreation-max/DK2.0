package com.dk.assistant

import com.dk.assistant.agent.master.BookRules
import com.dk.assistant.agent.master.BookRules.Kind
import com.dk.assistant.agent.master.BookRules.Tap
import com.dk.assistant.agent.master.MasterRules
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 4 - बुकिंग एजेंट) */
class BookingAgentTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun train_fullDetails() {
        val t = BookRules.trip("कल नासिक से पुणे की ट्रेन 2 लोगों के लिए बुक करो")
        assertEquals(Kind.TRAIN, t.kind)
        assertEquals("नासिक", t.from)
        assertEquals("पुणे", t.to)
        assertEquals("कल", t.date)
        assertEquals(2, t.people)
        assertTrue(BookRules.missing(t).isEmpty())
        assertEquals("ट्रेन टिकट: नासिक → पुणे, कल, 2 लोग", BookRules.summary(t))
    }

    @Test fun missing_isAsked_neverGuessed() {
        val t = BookRules.trip("ट्रेन टिकट बुक करो")
        assertEquals(listOf("कहाँ से", "कहाँ तक", "किस तारीख को", "कितने लोग"), BookRules.missing(t))
        assertNull(t.people)   // अपने से 1 नहीं मानते
        val n = BookRules.askNote(t, BookRules.missing(t))
        assertTrue(n.startsWith("ASK_USER"))
        assertTrue(n.contains("Do NOT guess"))
    }

    @Test fun cab_destinationOnly() {
        val t = BookRules.trip("स्टेशन के लिए Uber बुक करो")
        assertEquals(Kind.CAB, t.kind)
        assertEquals("स्टेशन", t.to)
        assertEquals("Uber", t.app?.label)
        assertTrue(BookRules.missing(t).isEmpty())
        assertEquals(listOf("कहाँ जाना है"), BookRules.missing(BookRules.trip("कैब बुक करो")))
    }

    @Test fun flight_english() {
        val t = BookRules.trip("book flight from mumbai to new delhi on 26 nov for 3 people")
        assertEquals(Kind.FLIGHT, t.kind)
        assertEquals("mumbai", t.from)
        assertEquals("new delhi", t.to)
        assertEquals("26 nov", t.date)
        assertEquals(3, t.people)
    }

    @Test fun hotel_cityDatePeople() {
        val t = BookRules.trip("गोवा में 3 तारीख से दो लोगों के लिए होटल बुक करो")
        assertEquals(Kind.HOTEL, t.kind)
        assertEquals("गोवा", t.to)
        assertEquals("3 तारीख", t.date)
        assertEquals(2, t.people)
        assertTrue(BookRules.missing(t).isEmpty())
    }

    @Test fun dates_peopleWords() {
        assertEquals("अगले शनिवार", BookRules.date("अगले शनिवार को बस टिकट"))
        assertEquals("26/11", BookRules.date("26/11 की ट्रेन"))
        assertEquals("परसों", BookRules.date("परसों मूवी"))
        assertEquals(1, BookRules.people("मेरे लिए पुणे की बस"))
        assertEquals(2, BookRules.people("हम दोनों के लिए"))
    }

    @Test fun intent_openIsNotBooking() {
        assertFalse(BookRules.isBooking("Uber खोलो"))
        assertTrue(BookRules.isBooking("स्टेशन के लिए कैब चाहिए"))
        assertTrue(BookRules.isBooking("IRCTC से टिकट बुक करो"))
    }

    @Test fun buttons_bookAsk_payNever() {
        assertEquals(Tap.BOOK, BookRules.tap("Book Now"))
        assertEquals(Tap.BOOK, BookRules.tap("Confirm Uber Go"))
        assertEquals(Tap.BOOK, BookRules.tap("Reserve"))
        assertEquals(Tap.PAY, BookRules.tap("Pay & Book"))
        assertEquals(Tap.PAY, BookRules.tap("Proceed to Pay"))
        assertEquals(Tap.BOOK, BookRules.tap("Confirm", booking = true))
        assertNull(BookRules.tap("Confirm"))
        assertNull(BookRules.tap("Search Buses"))
    }

    @Test fun agentNote_strict() {
        val t = BookRules.trip("कल नासिक से पुणे की ट्रेन 2 लोगों के लिए बुक करो")
        val apps = BookRules.appsFor(t.kind) { it == "cris.org.in.prs.ima" || it == "com.ixigo.train.ixitrain" }
        assertEquals(listOf("IRCTC Rail Connect", "ixigo"), apps.map { it.label })
        val n = BookRules.agentNote(t, apps)
        assertTrue(n.contains("NEVER make the online payment"))
        assertTrue(n.contains("never make them up"))
        assertTrue(n.contains("first ask which one"))
        assertTrue(BookRules.askLine(t, "Book Now").contains("नासिक → पुणे"))
        assertEquals(MasterRules.Specialist.BOOKING, MasterRules.classify("स्टेशन के लिए Uber बुक करो"))
    }

    @Test fun wiring_onlyWhenMasterOn() {
        val m = src("agent/master/DkMaster.kt")
        assertTrue(m.contains("BookRules.isBooking(req)"))
        assertTrue(m.contains("BookRules.askNote(t, miss)"))
        assertTrue(m.contains("if (!on(c)) return null to null"))
        val r = src("AgentRunner.kt")
        assertTrue(r.contains("BookRules.askLine(trip, bk)"))
        assertTrue(src("SettingsBrain.kt").contains("बुकिंग एजेंट"))
    }
}
