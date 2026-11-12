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

import java.io.StringReader
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone

/** One .ics file from the resources of a calendar, for other calendar apps. */
object IcsExport {
    private const val PRODUCT_ID = "-//Grit//Calendar export//EN"

    /**
     * Joins iCalendar resources into one file: every event with its overrides, and each time zone
     * once. Text that is not iCalendar is left out. [name] becomes X-WR-CALNAME, which most apps
     * show as the name of the imported calendar.
     */
    fun join(resources: List<String>, name: String?): String {
        val zones = LinkedHashMap<String, String>()
        val events = StringBuilder()
        for (text in resources) {
            val calendar = runCatching { CalendarBuilder().build(StringReader(text)) }.getOrNull()
            if (calendar == null) continue
            for (zone in calendar.getComponents<VTimeZone>(Component.VTIMEZONE)) {
                val id = zone.getProperty<Property>(Property.TZID).orElse(null)?.value ?: continue
                zones.putIfAbsent(id, zone.toString())
            }
            for (event in calendar.getComponents<VEvent>(Component.VEVENT)) {
                events.append(event.toString())
            }
        }
        return buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:$PRODUCT_ID\r\n")
            if (!name.isNullOrBlank()) append("X-WR-CALNAME:").append(escape(name)).append("\r\n")
            for (zone in zones.values) append(zone)
            append(events)
            append("END:VCALENDAR\r\n")
        }
    }

    /** TEXT value escaping of RFC 5545: backslash, semicolon, comma and line breaks. */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
}
