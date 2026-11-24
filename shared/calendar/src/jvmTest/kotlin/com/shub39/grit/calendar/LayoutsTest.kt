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

import com.shub39.grit.calendar.ui.home.DayEvent
import com.shub39.grit.calendar.ui.home.layoutTimed
import com.shub39.grit.calendar.ui.home.layoutWeek
import com.shub39.grit.calendar.ui.home.placeByTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class LayoutsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val monday = LocalDate(2026, 11, 9)
    private var nextId = 1L

    private fun event(
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        allDay: Boolean = false,
        endDay: LocalDate = end.date,
    ): DayEvent {
        val tz = TimeZone.of(zone.id)
        val id = nextId++
        return DayEvent(
            instanceId = id,
            eventId = id,
            title = title,
            location = null,
            begin = start.toInstant(tz).toEpochMilliseconds(),
            end = end.toInstant(tz).toEpochMilliseconds(),
            allDay = allDay,
            startDay = start.date,
            endDay = endDay,
            originalStart = null,
            recurring = false,
            category = null,
            color = null,
            calendarName = null,
            canWrite = true,
        )
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 11, day, hour, minute)

    @Test
    fun `a multi-day event is one bar and keeps its lane`() {
        val trip =
            event("Trip", at(10, 0), at(13, 0), allDay = true, endDay = LocalDate(2026, 11, 12))
        val dentist = event("Dentist", at(11, 9), at(11, 10))
        val lunch = event("Lunch", at(9, 12), at(9, 13))
        val lanes = layoutWeek(monday, listOf(dentist, lunch, trip), maxLanes = 4, zone)
        val tripBar = lanes.segments.single { it.event == trip }
        assertEquals(1, tripBar.startColumn)
        assertEquals(3, tripBar.endColumn)
        assertEquals(0f, tripBar.position)
        // Below the bar of its day. 9:00 is 3/16 of the way from 6:00 to 22:00
        assertEquals(1f + 3f / 16 * 2, lanes.segments.single { it.event == dentist }.position)
        // No bar on Monday: noon is 6/16 of the way down the four lines
        assertEquals(6f / 16 * 3, lanes.segments.single { it.event == lunch }.position)
        assertTrue(lanes.hidden.all { it == 0 })
    }

    @Test
    fun `events are lower for a later time and never overlap`() {
        val early = event("Early", at(9, 7), at(9, 8))
        val soon = event("Soon after", at(9, 7, 30), at(9, 8))
        val night = event("Night", at(9, 21), at(9, 22))
        val positions =
            layoutWeek(monday, listOf(night, soon, early), maxLanes = 4, zone).segments.associate {
                it.event.title to it.position
            }
        assertEquals(1f / 16 * 3, positions.getValue("Early"))
        // One line below the event before it, because it would overlap at its own time
        assertEquals(positions.getValue("Early") + 1f, positions.getValue("Soon after"))
        assertEquals(15f / 16 * 3, positions.getValue("Night"))
    }

    @Test
    fun `late events move up to stay in the cell`() {
        assertEquals(listOf(1f, 2f, 3f), placeByTime(listOf(1f, 1f, 1f), top = 0, bottom = 4))
        assertEquals(listOf(1f, 2f), placeByTime(listOf(0f, 1f), top = 1, bottom = 3))
    }

    @Test
    fun `an event across weeks continues on both sides`() {
        val holidays =
            event(
                "Holidays",
                LocalDateTime(2026, 11, 6, 0, 0),
                at(18, 0),
                allDay = true,
                endDay = LocalDate(2026, 11, 17),
            )
        val bar = layoutWeek(monday, listOf(holidays), maxLanes = 4, zone).segments.single()
        assertEquals(0, bar.startColumn)
        assertEquals(6, bar.endColumn)
        assertTrue(bar.continuesBefore)
        assertTrue(bar.continuesAfter)
    }

    @Test
    fun `a full day keeps its last lane for the hidden count`() {
        val events = (0 until 6).map { event("E$it", at(12, 8 + it), at(12, 9 + it)) }
        val lanes = layoutWeek(monday, events, maxLanes = 4, zone)
        assertEquals(3, lanes.segments.size)
        assertEquals(3, lanes.hidden[3])
        assertEquals(listOf(0f, 1f, 2f), lanes.segments.map { it.position })
    }

    @Test
    fun `overlapping events share the width of the day`() {
        val a = event("A", at(9, 9), at(9, 11))
        val b = event("B", at(9, 10), at(9, 12))
        val c = event("C", at(9, 11), at(9, 12))
        val d = event("D", at(9, 14), at(9, 15))
        val night = event("Night", at(10, 22), at(11, 2))
        val boxes = layoutTimed(monday, listOf(a, b, c, d, night), zone)
        val byTitle = boxes.groupBy { it.event.title }
        assertEquals(0, byTitle.getValue("A").single().slot)
        assertEquals(1, byTitle.getValue("B").single().slot)
        // C starts when A ends, so it reuses A's slot in the same group
        assertEquals(0, byTitle.getValue("C").single().slot)
        assertEquals(2, byTitle.getValue("A").single().slots)
        assertEquals(1, byTitle.getValue("D").single().slots)
        // An event across midnight gets one box per day
        val nightBoxes = byTitle.getValue("Night")
        assertEquals(listOf(1, 2), nightBoxes.map { it.column })
        assertEquals(22 * 60, nightBoxes[0].startMinute)
        assertEquals(24 * 60, nightBoxes[0].endMinute)
        assertEquals(120, nightBoxes[1].endMinute)
    }
}
