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
import com.shub39.grit.caldav.ical.EventResource
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CollectionEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ImportEventsTest {
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val dao = db.calendarDao()
    private val store = CalendarStore(db)

    @AfterTest fun close() = db.close()

    private fun event(uid: String, summary: String, status: String? = null) =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261101T000000Z\r\n" +
            "DTSTART;VALUE=DATE:20261224\r\nDTEND;VALUE=DATE:20261225\r\nSUMMARY:$summary\r\n" +
            (if (status != null) "STATUS:$status\r\n" else "") +
            "CATEGORIES:Family\r\nCOLOR:crimson\r\nEND:VEVENT\r\n"

    private fun file(vararg events: String) =
        EventResource.splitFile(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n" +
                events.joinToString("") +
                "END:VCALENDAR\r\n"
        )

    @Test
    fun `events go into a calendar on this device, and a second import updates them`() =
        runBlocking {
            val calendar = store.createLocalCalendar("Personal")
            val first =
                store.importEvents(
                    calendar,
                    file(
                        event("a@x", "Christmas"),
                        event("b@x", "Party"),
                        event("c@x", "Cancelled", "CANCELLED"),
                    ),
                )
            assertEquals(CalendarStore.ImportResult(added = 2, updated = 0, skipped = 1), first)

            val second =
                store.importEvents(
                    calendar,
                    file(event("a@x", "Christmas Eve"), event("d@x", "New")),
                )
            assertEquals(CalendarStore.ImportResult(added = 1, updated = 1, skipped = 0), second)

            val events = dao.eventsOf(calendar)
            assertEquals(listOf("Christmas Eve", "Party", "New"), events.map { it.summary })
            // Nothing to upload from a calendar on this device
            assertTrue(events.none { it.dirty })
            assertEquals(
                "crimson",
                dao.categoriesFlow().first().single { it.name == "Family" }.color,
            )
        }

    @Test
    fun `events in a server calendar wait for the next sync`() = runBlocking {
        val account = dao.upsertAccount(AccountEntity(baseUrl = "https://dav", username = "bob"))
        val calendar =
            dao.insertCollection(
                CollectionEntity(accountId = account, url = "https://dav/bob/work/")
            )
        store.importEvents(calendar, file(event("weird uid/with spaces", "Meeting")))
        val event = dao.eventsOf(calendar).single()
        assertTrue(event.dirty)
        assertFalse(event.existsOnServer)
        assertEquals("weird uid/with spaces", event.uid)
        // The href is safe in a URL, whatever the UID
        assertTrue(event.href.startsWith("https://dav/bob/work/"))
        assertFalse(event.href.removePrefix("https://dav/bob/work/").contains(Regex("[ /]")))
    }
}
