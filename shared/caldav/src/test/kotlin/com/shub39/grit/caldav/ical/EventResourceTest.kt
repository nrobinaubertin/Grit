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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class EventResourceTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-09T12:00:00Z")

    private fun ics(vararg events: String, extra: String = "") =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" +
            events.joinToString("") {
                "BEGIN:VEVENT\r\n" + it.trimIndent().replace("\n", "\r\n") + "\r\nEND:VEVENT\r\n"
            } +
            extra +
            "END:VCALENDAR\r\n"

    private fun expand(ics: String, from: String, to: String) =
        EventResource.parse(ics).expand(Instant.parse(from), Instant.parse(to), paris)

    @Test
    fun `zoned event keeps local time across DST`() {
        val resource =
            ics(
                """
                UID:weekly
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20260322T100000
                DTEND;TZID=Europe/Paris:20260322T110000
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Weekly
                """
            )
        val occurrences = expand(resource, "2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z")
        assertEquals(
            listOf("2026-03-22T09:00:00Z", "2026-03-29T08:00:00Z", "2026-04-05T08:00:00Z"),
            occurrences.map { it.start.toString() },
        )
        assertTrue(occurrences.all { it.end.epochSeconds - it.start.epochSeconds == 3600L })
        assertTrue(occurrences.all { it.originalStart == it.start })
    }

    @Test
    fun `all-day event uses midnight UTC and lasts one day by default`() {
        val resource =
            ics(
                """
                UID:allday
                DTSTAMP:20260101T000000Z
                DTSTART;VALUE=DATE:20260301
                SUMMARY:Holiday
                """
            )
        val occurrence = expand(resource, "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").single()
        assertTrue(occurrence.allDay)
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), occurrence.start)
        assertEquals(Instant.parse("2026-03-02T00:00:00Z"), occurrence.end)
        assertNull(occurrence.originalStart)
        val info = EventResource.parse(resource).info()!!
        assertEquals(IcalTime.Date(LocalDate(2026, 3, 1)), info.start)
        assertEquals(IcalTime.Date(LocalDate(2026, 3, 2)), info.end)
    }

    @Test
    fun `floating event is placed in the device zone`() {
        val resource =
            ics(
                """
                UID:floating
                DTSTAMP:20260101T000000Z
                DTSTART:20260701T100000
                DURATION:PT30M
                """
            )
        val occurrence = expand(resource, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z").single()
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), occurrence.start)
        assertEquals(Instant.parse("2026-07-01T08:30:00Z"), occurrence.end)
        val info = EventResource.parse(resource).info()!!
        assertEquals(IcalTime.Floating(LocalDateTime(2026, 7, 1, 10, 0)), info.start)
        assertEquals(IcalTime.Floating(LocalDateTime(2026, 7, 1, 10, 30)), info.end)
    }

    @Test
    fun `UNTIL, EXDATE and overrides shape the series`() {
        val resource =
            ics(
                """
                UID:series
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20260302T090000
                DTEND;TZID=Europe/Paris:20260302T093000
                RRULE:FREQ=DAILY;UNTIL=20260306T080000Z
                EXDATE;TZID=Europe/Paris:20260303T090000
                SUMMARY:Standup
                """,
                """
                UID:series
                DTSTAMP:20260101T000000Z
                RECURRENCE-ID;TZID=Europe/Paris:20260304T090000
                DTSTART;TZID=Europe/Paris:20260304T140000
                DTEND;TZID=Europe/Paris:20260304T143000
                SUMMARY:Standup (moved)
                """,
                """
                UID:series
                DTSTAMP:20260101T000000Z
                RECURRENCE-ID:20260305T080000Z
                DTSTART;TZID=Europe/Paris:20260305T090000
                STATUS:CANCELLED
                """,
            )
        val occurrences = expand(resource, "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z")
        assertEquals(
            listOf(
                "2026-03-02T08:00:00Z" to "Standup",
                "2026-03-04T13:00:00Z" to "Standup (moved)",
                "2026-03-06T08:00:00Z" to "Standup",
            ),
            occurrences.map { it.start.toString() to it.summary },
        )
        val moved = occurrences[1]
        assertTrue(moved.isOverride)
        assertEquals(Instant.parse("2026-03-04T08:00:00Z"), moved.originalStart)
        assertTrue(EventResource.parse(resource).info()!!.hasExceptions)
    }

    @Test
    fun `occurrences that started before the window are included`() {
        val resource =
            ics(
                """
                UID:long
                DTSTAMP:20260101T000000Z
                DTSTART;VALUE=DATE:20260101
                DTEND;VALUE=DATE:20260104
                RRULE:FREQ=MONTHLY
                """
            )
        val occurrences = expand(resource, "2026-02-02T00:00:00Z", "2026-02-03T00:00:00Z")
        assertEquals(listOf(Instant.parse("2026-02-01T00:00:00Z")), occurrences.map { it.start })
    }

    @Test
    fun `old daily series expands only inside the window`() {
        val resource =
            ics(
                """
                UID:old
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20100101T070000
                DTEND;TZID=Europe/Paris:20100101T071500
                RRULE:FREQ=DAILY
                """
            )
        val occurrences = expand(resource, "2026-11-01T00:00:00Z", "2026-11-08T00:00:00Z")
        assertEquals(7, occurrences.size)
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), occurrences.first().start)
    }

    @Test
    fun `alarms relative to start and end`() {
        val resource =
            ics(
                """
                UID:alarms
                DTSTAMP:20260101T000000Z
                DTSTART:20260310T100000Z
                DTEND:20260310T110000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER:-PT15M
                DESCRIPTION:x
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                TRIGGER;RELATED=END:PT0S
                DESCRIPTION:x
                END:VALARM
                BEGIN:VALARM
                ACTION:EMAIL
                TRIGGER:-PT1H
                DESCRIPTION:x
                SUMMARY:x
                ATTENDEE:mailto:a@example.com
                END:VALARM
                """
            )
        val occurrence = expand(resource, "2026-03-10T00:00:00Z", "2026-03-11T00:00:00Z").single()
        assertEquals(
            listOf(Instant.parse("2026-03-10T09:45:00Z"), Instant.parse("2026-03-10T11:00:00Z")),
            occurrence.alarms,
        )
        assertEquals(listOf(15), EventResource.parse(resource).info()!!.reminderMinutes)
    }

    @Test
    fun `editing keeps unknown properties and bumps the sequence`() {
        val original =
            ics(
                """
                UID:keep
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20260310T100000
                DTEND;TZID=Europe/Paris:20260310T110000
                SEQUENCE:3
                SUMMARY:Old
                X-CUSTOM-THING:keep me
                ATTENDEE;CN=Bob:mailto:bob@example.com
                """
            )
        val resource = EventResource.parse(original)
        val info = resource.info()!!
        val edited =
            resource.edit(
                EventDraft(
                    summary = "New, with comma",
                    start = info.start,
                    end = info.end,
                    location = "Home",
                    reminderMinutes = listOf(10),
                ),
                now,
            )
        val text = edited.toIcs()
        assertTrue("X-CUSTOM-THING:keep me" in text)
        assertTrue("ATTENDEE;CN=Bob:mailto:bob@example.com" in text)
        assertTrue("DTSTART;TZID=Europe/Paris:20260310T100000" in text)
        val reparsed = EventResource.parse(text).info()!!
        assertEquals("New, with comma", reparsed.summary)
        assertEquals("Home", reparsed.location)
        assertEquals(4, reparsed.sequence)
        assertEquals(listOf(10), reparsed.reminderMinutes)
        assertEquals(now, reparsed.lastModified)
    }

    @Test
    fun `changing the start drops overrides and exceptions`() {
        val original =
            ics(
                """
                UID:series
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20260302T090000
                DTEND;TZID=Europe/Paris:20260302T093000
                RRULE:FREQ=DAILY;COUNT=5
                EXDATE;TZID=Europe/Paris:20260303T090000
                """,
                """
                UID:series
                DTSTAMP:20260101T000000Z
                RECURRENCE-ID;TZID=Europe/Paris:20260304T090000
                DTSTART;TZID=Europe/Paris:20260304T140000
                """,
            )
        val resource = EventResource.parse(original)
        val info = resource.info()!!
        val edited =
            resource.edit(
                EventDraft(
                    summary = "Moved series",
                    start = IcalTime.Zoned(LocalDateTime(2026, 3, 2, 10, 0), "Europe/Paris"),
                    end = IcalTime.Zoned(LocalDateTime(2026, 3, 2, 10, 30), "Europe/Paris"),
                    rrule = info.rrule,
                ),
                now,
            )
        val occurrences =
            edited.expand(
                Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                paris,
            )
        assertEquals(5, occurrences.size)
        assertFalse(edited.info()!!.hasExceptions)
    }

    @Test
    fun `excluding one occurrence adds an EXDATE with the TZID`() {
        val original =
            ics(
                """
                UID:series
                DTSTAMP:20260101T000000Z
                DTSTART;TZID=Europe/Paris:20260302T090000
                DTEND;TZID=Europe/Paris:20260302T093000
                RRULE:FREQ=DAILY;COUNT=3
                """
            )
        val edited =
            EventResource.parse(original)
                .excludeOccurrence(Instant.parse("2026-03-03T08:00:00Z"), paris, now)
        assertTrue("EXDATE;TZID=Europe/Paris:20260303T090000" in edited.toIcs())
        val starts =
            edited
                .expand(
                    Instant.parse("2026-03-01T00:00:00Z"),
                    Instant.parse("2026-04-01T00:00:00Z"),
                    paris,
                )
                .map { it.start.toString() }
        assertEquals(listOf("2026-03-02T08:00:00Z", "2026-03-04T08:00:00Z"), starts)
    }

    @Test
    fun `created event has a compact VTIMEZONE and round-trips`() {
        val resource =
            EventResource.create(
                uid = "new-uid",
                draft =
                    EventDraft(
                        summary = "Dinner; with friends",
                        start = IcalTime.Zoned(LocalDateTime(2026, 7, 4, 19, 0), "Europe/Paris"),
                        end = IcalTime.Zoned(LocalDateTime(2026, 7, 4, 21, 0), "Europe/Paris"),
                        rrule = "FREQ=YEARLY",
                        reminderMinutes = listOf(60, 0),
                    ),
                now = now,
            )
        val text = resource.toIcs()
        assertTrue("BEGIN:VTIMEZONE" in text)
        assertTrue("TZID:Europe/Paris" in text)
        assertTrue(text.length < 2000, "VTIMEZONE is not compact: ${text.length} characters")
        val info = EventResource.parse(text).info()!!
        assertEquals("new-uid", info.uid)
        assertEquals("Dinner; with friends", info.summary)
        assertEquals("FREQ=YEARLY", info.rrule)
        assertEquals(listOf(0, 60), info.reminderMinutes)
        assertEquals(0, info.sequence)
        val occurrence =
            EventResource.parse(text)
                .expand(
                    Instant.parse("2027-07-01T00:00:00Z"),
                    Instant.parse("2027-07-31T00:00:00Z"),
                    paris,
                )
                .single()
        assertEquals(Instant.parse("2027-07-04T17:00:00Z"), occurrence.start)
        assertNotNull(occurrence.originalStart)
    }

    @Test
    fun `category and color are read, written and kept`() {
        val original =
            ics(
                """
                UID:cat
                DTSTAMP:20260101T000000Z
                DTSTART:20260310T100000Z
                CATEGORIES:Family,Holidays
                COLOR:turquoise
                """
            )
        val info = EventResource.parse(original).info()!!
        assertEquals("Family", info.category)
        assertEquals("turquoise", info.color)

        // Editing another field keeps the categories as they are
        val edited =
            EventResource.parse(original)
                .edit(
                    EventDraft(
                        summary = "x",
                        start = info.start,
                        end = info.end,
                        category = "Family",
                        color = "turquoise",
                    ),
                    now,
                )
        assertTrue("CATEGORIES:Family,Holidays" in edited.toIcs())

        val recolored = EventResource.parse(original).withCategory("Work", "royalblue", now)
        val text = recolored.toIcs()
        assertTrue("CATEGORIES:Work" in text)
        assertTrue("COLOR:royalblue" in text)
        assertEquals("Work", EventResource.parse(text).info()!!.category)

        val cleared = recolored.withCategory(null, null, now).toIcs()
        assertFalse("CATEGORIES" in cleared)
        assertFalse("COLOR:" in cleared)
    }

    @Test
    fun `generated VTIMEZONE matches java time zone rules`() {
        for (systemId in
            listOf("Europe/Paris", "America/New_York", "Australia/Sydney", "Asia/Tokyo")) {
            val zone = ZoneId.of(systemId)
            // A TZID unknown to the system forces ical4j to use the VTIMEZONE definition
            val id = "Custom/$systemId"
            val text = vTimeZoneText(zone).replace("TZID:$systemId", "TZID:$id")
            val resource =
                EventResource.parse(
                    ics(
                        """
                        UID:tz
                        DTSTAMP:20260101T000000Z
                        DTSTART;TZID=$id:20260115T120000
                        RRULE:FREQ=MONTHLY;COUNT=12
                        """,
                        extra = text,
                    )
                )
            val starts =
                resource.expand(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2027-01-01T00:00:00Z"),
                    paris,
                )
            assertEquals(12, starts.size, id)
            for (occurrence in starts) {
                val expected =
                    java.time.Instant.ofEpochSecond(occurrence.start.epochSeconds)
                        .atZone(zone)
                        .toLocalTime()
                assertEquals(java.time.LocalTime.NOON, expected, "$id ${occurrence.start}")
            }
        }
    }
}
