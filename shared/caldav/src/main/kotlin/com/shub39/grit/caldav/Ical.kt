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
package com.shub39.grit.caldav

import java.io.StringReader
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent

/** Entry point for iCalendar parsing. */
object Ical {
    /** Parses a tiny calendar to check that ical4j works on this platform. Returns "ok". */
    fun libraryCheck(): String {
        val ics =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//EN\r\nBEGIN:VEVENT\r\n" +
                "UID:check\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Europe/Paris:20260101T100000\r\n" +
                "SUMMARY:check\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val calendar = CalendarBuilder().build(StringReader(ics))
        return if (calendar.getComponents<VEvent>("VEVENT").size == 1) "ok" else "failed"
    }
}
