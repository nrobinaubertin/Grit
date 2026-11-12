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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcsExportTest {
    private val zone =
        "BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nBEGIN:STANDARD\r\nDTSTART:19701025T030000\r\n" +
            "TZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"

    private fun resource(uid: String, summary: String, extra: String = "") =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" +
            zone +
            "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261101T000000Z\r\n" +
            "DTSTART;TZID=Europe/Paris:20261113T090000\r\nDTEND;TZID=Europe/Paris:20261113T100000\r\n" +
            "SUMMARY:$summary\r\n$extra" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test
    fun `events join one file with each time zone once, and import again`() {
        val override =
            "BEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20261101T000000Z\r\n" +
                "RECURRENCE-ID;TZID=Europe/Paris:20261120T090000\r\n" +
                "DTSTART;TZID=Europe/Paris:20261120T110000\r\nDTEND;TZID=Europe/Paris:20261120T120000\r\n" +
                "SUMMARY:Weekly, moved\r\nEND:VEVENT\r\n"
        val series =
            resource("weekly", "Weekly", "RRULE:FREQ=WEEKLY;COUNT=4\r\n")
                .replace("END:VCALENDAR", override + "END:VCALENDAR")
        val text =
            IcsExport.join(
                listOf(resource("dentist", "Dentist"), series, "not iCalendar"),
                "Family; home",
            )
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(text).count(), text)
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(text).count(), text)
        assertTrue("X-WR-CALNAME:Family\\; home\r\n" in text, text)
        // An import reads the series with its override as one resource
        val back = EventResource.splitFile(text)
        assertEquals(setOf("dentist", "weekly"), back.map { it.uid }.toSet())
        assertEquals("Weekly", back.single { it.uid == "weekly" }.info()?.summary)
    }

    @Test
    fun `an empty calendar gives a valid empty file`() {
        val text = IcsExport.join(emptyList(), null)
        assertEquals(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//Calendar export//EN\r\nEND:VCALENDAR\r\n",
            text,
        )
    }
}
