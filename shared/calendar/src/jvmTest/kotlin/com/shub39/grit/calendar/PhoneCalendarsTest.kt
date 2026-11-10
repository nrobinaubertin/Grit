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
package com.shub39.grit.calendar

import com.shub39.grit.caldav.ical.EventResource
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.calendar.data.PhoneCalendarSource
import com.shub39.grit.calendar.data.PhoneCalendars
import com.shub39.grit.calendar.data.PhoneEventRow
import com.shub39.grit.calendar.data.PhoneIcs
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class PhoneCalendarsTest {
    private val now = java.time.Instant.parse("2026-11-10T12:00:00Z")
    private val paris = ZoneId.of("Europe/Paris")

    private fun millis(text: String) = java.time.Instant.parse(text).toEpochMilli()

    private fun row(
        id: Long,
        start: String,
        end: String? = null,
        allDay: Boolean = false,
        title: String = "Event $id",
        timeZone: String? = "Europe/Paris",
        uid: String? = null,
        duration: String? = null,
        rrule: String? = null,
        exdate: String? = null,
        originalId: Long? = null,
        originalInstanceTime: String? = null,
        status: Int? = null,
        location: String? = null,
        reminders: List<Int> = emptyList(),
    ) =
        PhoneEventRow(
            id = id,
            uid = uid,
            title = title,
            description = null,
            location = location,
            start = millis(start),
            end = end?.let(::millis),
            duration = duration,
            allDay = allDay,
            timeZone = timeZone,
            rrule = rrule,
            rdate = null,
            exrule = null,
            exdate = exdate,
            originalId = originalId,
            originalInstanceTime = originalInstanceTime?.let(::millis),
            status = status,
            reminderMinutes = reminders,
        )

    private fun import(vararg rows: PhoneEventRow): List<EventResource> =
        EventResource.splitFile(PhoneIcs.build(calendarId = 4, rows.toList(), now))

    @Test
    fun `a timed event keeps its zone, text and reminder`() {
        val event =
            import(
                    row(
                        1,
                        "2026-11-12T14:00:00Z",
                        "2026-11-12T15:30:00Z",
                        title = "Lunch; with Bob, \\ friends",
                        location = "Rue A\nParis",
                        reminders = listOf(30, -1),
                    )
                )
                .single()
        val info = event.info()!!
        assertEquals("Lunch; with Bob, \\ friends", info.summary)
        assertEquals("Rue A\nParis", info.location)
        assertEquals(IcalTime.Zoned(LocalDateTime(2026, 11, 12, 15, 0), "Europe/Paris"), info.start)
        assertEquals(listOf(30), info.reminderMinutes)
        // A stable UID, so that a second import replaces the event
        assertEquals("android-4-1@grit", event.uid)
    }

    @Test
    fun `an all-day event covers its dates, and an app UID stays`() {
        val event =
            import(
                    row(
                        2,
                        "2026-12-24T00:00:00Z",
                        "2026-12-27T00:00:00Z",
                        allDay = true,
                        timeZone = "UTC",
                        uid = "holiday@google.com",
                    )
                )
                .single()
        assertEquals("holiday@google.com", event.uid)
        assertEquals(IcalTime.Date(LocalDate(2026, 12, 24)), event.info()!!.start)
        assertEquals(IcalTime.Date(LocalDate(2026, 12, 27)), event.info()!!.end)
    }

    @Test
    fun `a series keeps its exceptions, moved and cancelled occurrences`() {
        val resources =
            import(
                // Every Thursday 19:00 Paris, for one hour and a half, from 12 November
                row(
                    10,
                    "2026-11-12T18:00:00Z",
                    duration = "P5400S",
                    rrule = "FREQ=WEEKLY;COUNT=5",
                    exdate = "20261126T180000Z",
                    title = "Choir",
                ),
                // 19 November moves to 20:00
                row(
                    11,
                    "2026-11-19T19:00:00Z",
                    "2026-11-19T20:30:00Z",
                    originalId = 10,
                    originalInstanceTime = "2026-11-19T18:00:00Z",
                    title = "Choir (late)",
                ),
                // 3 December is cancelled
                row(
                    12,
                    "2026-12-03T18:00:00Z",
                    "2026-12-03T19:30:00Z",
                    originalId = 10,
                    originalInstanceTime = "2026-12-03T18:00:00Z",
                    status = 2,
                ),
            )
        val choir = resources.single()
        assertEquals("FREQ=WEEKLY;COUNT=5", choir.info()!!.rrule)
        val starts =
            choir
                .expand(
                    Instant.parse("2026-11-01T00:00:00Z"),
                    Instant.parse("2027-01-01T00:00:00Z"),
                    paris,
                )
                .map { it.start }
                .sorted()
        assertEquals(
            listOf(
                Instant.parse("2026-11-12T18:00:00Z"),
                Instant.parse("2026-11-19T19:00:00Z"),
                Instant.parse("2026-12-10T18:00:00Z"),
            ),
            starts,
        )
    }

    @Test
    fun `an all-day series gets EXDATE as dates, a cancelled series stays out`() {
        val resources =
            import(
                row(
                    20,
                    "2026-11-02T00:00:00Z",
                    duration = "P1D",
                    allDay = true,
                    timeZone = "UTC",
                    rrule = "FREQ=WEEKLY;COUNT=3",
                    exdate = "20261109T000000Z",
                    title = "Bins",
                ),
                row(21, "2026-11-05T10:00:00Z", "2026-11-05T11:00:00Z", status = 2),
            )
        val bins = resources.single()
        assertTrue("EXDATE;VALUE=DATE:20261109" in bins.toIcs())
        val days =
            bins
                .expand(
                    Instant.parse("2026-11-01T00:00:00Z"),
                    Instant.parse("2026-12-01T00:00:00Z"),
                    paris,
                )
                .map { it.start }
        assertEquals(
            listOf(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z")),
            days.sorted(),
        )
    }

    @Test
    fun `durations with and without T`() {
        assertEquals(5_400_000L, PhoneIcs.durationMillis("P5400S"))
        assertEquals(3_600_000L, PhoneIcs.durationMillis("PT1H"))
        assertEquals(86_400_000L, PhoneIcs.durationMillis("P1D"))
        assertEquals(90 * 60_000L, PhoneIcs.durationMillis("PT1H30M"))
        assertEquals(null, PhoneIcs.durationMillis("one hour"))
    }

    @Test
    fun `the desktop has no phone calendars`() = runBlocking {
        val phone = PhoneCalendars(PhoneCalendarSource())
        assertFalse(phone.supported)
        assertEquals(emptyList(), phone.calendars().first())
        assertTrue(EventResource.splitFile(phone.exportIcs(1)).isEmpty())
    }
}
