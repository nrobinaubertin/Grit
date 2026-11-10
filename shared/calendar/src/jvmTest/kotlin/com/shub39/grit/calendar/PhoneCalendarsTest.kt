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

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shub39.grit.calendar.data.PhoneCalendar
import com.shub39.grit.calendar.data.PhoneCalendarSource
import com.shub39.grit.calendar.data.PhoneCalendars
import com.shub39.grit.calendar.data.PhoneInstance
import com.shub39.grit.calendar.data.PhoneSettings
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.KeyValueEntity
import com.shub39.grit.calendar.ui.home.toDayEvent
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

class PhoneCalendarsTest {
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @AfterTest fun close() = db.close()

    private fun calendar(id: Long, visibleInProvider: Boolean) =
        PhoneCalendar(id, "Calendar $id", "me@example.com", 0xFF00FF00.toInt(), visibleInProvider)

    @Test
    fun `settings follow the provider until the user chooses`() {
        val settings =
            PhoneSettings.from(
                listOf(
                    KeyValueEntity(PhoneSettings.ENABLED, 1),
                    KeyValueEntity(PhoneSettings.CALENDAR + 2, 0),
                    KeyValueEntity(PhoneSettings.CALENDAR + 3, 1),
                    KeyValueEntity("window.start", 5),
                )
            )
        assertTrue(settings.enabled)
        assertTrue(settings.isVisible(calendar(1, visibleInProvider = true)))
        assertFalse(settings.isVisible(calendar(1, visibleInProvider = false)))
        assertFalse(settings.isVisible(calendar(2, visibleInProvider = true)))
        assertTrue(settings.isVisible(calendar(3, visibleInProvider = false)))
        assertFalse(PhoneSettings.from(emptyList()).enabled)
    }

    @Test
    fun `choices are saved, and the desktop shows nothing`() = runBlocking {
        val phone = PhoneCalendars(PhoneCalendarSource(), db)
        phone.setEnabled(true)
        phone.setVisible(7, false)
        val stored = PhoneSettings.from(db.calendarDao().valuesFlow(PhoneSettings.PREFIX).first())
        assertEquals(PhoneSettings(enabled = true, shown = mapOf(7L to false)), stored)
        // No calendar permission on desktop
        assertFalse(phone.active.first())
        assertEquals(emptyList(), phone.calendars().first())
        assertEquals(emptyList(), phone.instances(0, Long.MAX_VALUE).first())
    }

    @Test
    fun `phone events cover the right days and stay read-only`() {
        val paris = ZoneId.of("Europe/Paris")
        val day = 24 * 60 * 60 * 1000L
        val nov10Utc = 1_794_268_800_000L // 2026-11-10T00:00Z
        fun instance(begin: Long, end: Long, allDay: Boolean) =
            PhoneInstance(
                instanceId = 5,
                eventId = 9,
                calendarId = 1,
                begin = begin,
                end = end,
                allDay = allDay,
                title = "Market",
                location = null,
                color = 0xFF112233.toInt(),
                calendarName = "Phone",
                recurring = false,
            )

        // All-day dates do not move with the time zone
        val allDay = instance(nov10Utc, nov10Utc + 2 * day, allDay = true).toDayEvent(paris)
        assertEquals(LocalDate(2026, 11, 10), allDay.startDay)
        assertEquals(LocalDate(2026, 11, 11), allDay.endDay)
        val west =
            instance(nov10Utc, nov10Utc + day, allDay = true)
                .toDayEvent(ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate(2026, 11, 10), west.startDay)
        assertEquals(LocalDate(2026, 11, 10), west.endDay)

        // 23:30 to 00:30 in Paris covers two days
        val timed =
            instance(
                    nov10Utc + 22 * 3_600_000L + 1_800_000L,
                    nov10Utc + 23 * 3_600_000L + 1_800_000L,
                    false,
                )
                .toDayEvent(paris)
        assertEquals(LocalDate(2026, 11, 10), timed.startDay)
        assertEquals(LocalDate(2026, 11, 11), timed.endDay)
        assertTrue(timed.phone)
        assertFalse(timed.canWrite)
        assertEquals("p5", timed.key)
        assertEquals(0xFF112233.toInt(), timed.color)
    }
}
