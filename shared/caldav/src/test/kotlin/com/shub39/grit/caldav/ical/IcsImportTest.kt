/*
 * Copyright (C) 2026  Niels Robin-Aubertin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shub39.grit.caldav.ical

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class IcsImportTest {
    /** Shaped like the export of another calendar app, with lines in LF only. */
    private val file =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Other app//EN
        METHOD:PUBLISH
        X-WR-CALNAME:Exported
        BEGIN:VTIMEZONE
        TZID:Custom/Paris
        BEGIN:STANDARD
        DTSTART:19701025T030000
        TZOFFSETFROM:+0200
        TZOFFSETTO:+0100
        RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:19700329T020000
        TZOFFSETFROM:+0100
        TZOFFSETTO:+0200
        RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
        END:DAYLIGHT
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:choir@example.com
        DTSTAMP:20261101T000000Z
        DTSTART;TZID=Custom/Paris:20261112T190000
        DTEND;TZID=Custom/Paris:20261112T203000
        RRULE:FREQ=WEEKLY;COUNT=4
        SUMMARY:Choir
        X-OTHER-APP:kept
        END:VEVENT
        BEGIN:VTODO
        UID:todo-1
        SUMMARY:Not an event
        END:VTODO
        BEGIN:VEVENT
        UID:choir@example.com
        RECURRENCE-ID;TZID=Custom/Paris:20261119T190000
        DTSTAMP:20261101T000000Z
        DTSTART;TZID=Custom/Paris:20261119T200000
        DTEND;TZID=Custom/Paris:20261119T213000
        SUMMARY:Choir (late)
        END:VEVENT
        BEGIN:VEVENT
        UID:holiday-1
        DTSTAMP:20261101T000000Z
        DTSTART;VALUE=DATE:20261224
        DTEND;VALUE=DATE:20261226
        SUMMARY:Holidays
        END:VEVENT
        BEGIN:VEVENT
        DTSTAMP:20261101T000000Z
        DTSTART;TZID=Europe/Berlin:20261201T100000
        DTEND;TZID=Europe/Berlin:20261201T110000
        SUMMARY:No UID
        END:VEVENT
        END:VCALENDAR
        """
            .trimIndent()

    @Test
    fun `a file splits into one resource per UID`() {
        val resources = EventResource.splitFile(file)
        assertEquals(3, resources.size)
        assertEquals(listOf("Choir", "Holidays", "No UID"), resources.map { it.info()?.summary })

        val choir = resources[0]
        assertEquals("choir@example.com", choir.uid)
        val ics = choir.toIcs()
        // The series keeps its changed occurrence, its own time zone and unknown properties
        assertTrue("RECURRENCE-ID" in ics)
        assertTrue("TZID:Custom/Paris" in ics)
        assertTrue("X-OTHER-APP:kept" in ics)
        // Calendar-level properties and other components stay out
        assertFalse("METHOD" in ics)
        assertFalse("VTODO" in ics)
        assertFalse("X-WR-CALNAME" in ics)
        val occurrences =
            choir.expand(
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-31T00:00:00Z"),
                ZoneId.of("Europe/Paris"),
            )
        assertEquals(4, occurrences.size)
        // 20:00 in Paris is 19:00 UTC: the override replaced the 18:00 UTC occurrence
        assertTrue(occurrences.any { it.start == Instant.parse("2026-11-19T19:00:00Z") })

        assertEquals(
            IcalTime.Date(kotlinx.datetime.LocalDate(2026, 12, 24)),
            resources[1].info()?.start,
        )
    }

    @Test
    fun `an event without UID gets one, with a time zone it can use`() {
        val noUid = EventResource.splitFile(file).last()
        val uid = assertNotNull(noUid.uid)
        assertTrue(uid.isNotBlank())
        // The file defines no Europe/Berlin zone, so Grit adds one
        assertTrue("TZID:Europe/Berlin" in noUid.toIcs())
        assertEquals(uid, EventResource.parse(noUid.toIcs()).uid)
    }

    @Test
    fun `a file without events gives nothing`() {
        val todos =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VTODO\r\nUID:t\r\n" +
                "SUMMARY:Task\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        assertEquals(emptyList(), EventResource.splitFile(todos))
    }
}
