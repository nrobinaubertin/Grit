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
import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.Credentials
import com.shub39.grit.caldav.KtorDavClient
import com.shub39.grit.caldav.WriteCondition
import com.shub39.grit.caldav.WriteResult
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.db.CalendarDatabase
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.Assume.assumeTrue

/**
 * "Start syncing" and "Stop syncing" against `server/radicale`. Skipped unless RADICALE_URL is set.
 */
class SyncToggleIntegrationTest {
    private val baseUrl = System.getenv("RADICALE_URL").orEmpty().trimEnd('/')
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val dao = db.calendarDao()
    private val store = CalendarStore(db)

    @AfterTest fun close() = db.close()

    private suspend fun bob(): CalendarSyncManager {
        System.setProperty("grit.calendar.dir", Files.createTempDirectory("grit-toggle").toString())
        val manager =
            CalendarSyncManager(
                db,
                store,
                CredentialStore(),
                ReminderScheduler(),
                com.shub39.grit.calendar.tasks.TaskListSync(db, store, FakeTaskRepo(), NoAlarms),
            )
        manager.addAccount(baseUrl, "bob", "bob", manager.discover(baseUrl, "bob", "bob"))
        manager.syncAll()
        return manager
    }

    private fun draft(summary: String, day: Int) =
        EventDraft(
            summary = summary,
            start = IcalTime.Zoned(LocalDateTime(2026, 12, day, 8, 0), "Europe/Paris"),
            end = IcalTime.Zoned(LocalDateTime(2026, 12, day, 9, 0), "Europe/Paris"),
        )

    private fun ics(uid: String, summary: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
            "DTSTAMP:20261101T000000Z\r\nDTSTART:20261205T080000Z\r\nDTEND:20261205T090000Z\r\n" +
            "SUMMARY:$summary\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Test
    fun `a calendar of the user alone goes to the server, then is deleted there`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        val manager = bob()
        val calendar = store.createLocalCalendar("Trip notes")
        store.createEvent(calendar, draft("Ferry", 1))
        store.createEvent(calendar, draft("Walk", 2).copy(rrule = "FREQ=WEEKLY;COUNT=3"))

        manager.startSyncing(calendar)
        val url = dao.collection(calendar)!!.url
        assertTrue(url.startsWith("$baseUrl/bob/"), url)
        assertTrue(dao.eventsOf(calendar).all { !it.dirty && it.etag != null })

        KtorDavClient(baseUrl, Credentials("bob", "bob")).use { dav ->
            assertEquals(2, dav.listEtags(url, ComponentType.VEVENT).size)
            assertEquals(CalendarSyncManager.StopResult.Deleted, manager.stopSyncing(calendar))
            // Gone from the server, kept on the device
            assertNull(dav.collection(url))
            assertTrue(dao.collection(calendar)!!.url.startsWith(CalendarStore.LOCAL_URL_PREFIX))
            assertEquals(listOf("Ferry", "Walk"), dao.eventsOf(calendar).map { it.summary })
        }
    }

    @Test
    fun `a shared calendar stays on the server, and comes back with both sides' changes`() =
        runBlocking {
            assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
            val family = "$baseUrl/alice/family/"
            val kept = "kept-${UUID.randomUUID()}"
            val gone = "gone-${UUID.randomUUID()}"
            val added = "added-${UUID.randomUUID()}"
            KtorDavClient(baseUrl, Credentials("alice", "alice")).use { alice ->
                try {
                    alice.put("$family$kept.ics", ics(kept, "Kept"), WriteCondition.MustNotExist)
                    alice.put("$family$gone.ics", ics(gone, "Gone"), WriteCondition.MustNotExist)
                    val manager = bob()
                    val calendars = dao.collections(dao.accounts().single { !it.local }.id).size
                    val shared =
                        dao.collections(dao.accounts().single { !it.local }.id).single {
                            it.url == "$baseUrl/bob/family/"
                        }

                    // alice shares this calendar with bob: it stays on the server
                    assertEquals(
                        CalendarSyncManager.StopResult.KeptShared,
                        manager.stopSyncing(shared.id),
                    )
                    // bob changes an event while the calendar does not sync
                    Thread.sleep(1100)
                    val keptEvent = dao.eventsOf(shared.id).single { it.uid == kept }
                    store.updateEvent(keptEvent.id, draft("Kept - bob", 5))
                    // alice adds one event and deletes another meanwhile
                    alice.put("$family$added.ics", ics(added, "Added"), WriteCondition.MustNotExist)
                    alice.delete("$family$gone.ics", WriteCondition.None)

                    manager.startSyncing(shared.id)
                    // The same server calendar again: no new calendar on the server
                    assertEquals("$baseUrl/bob/family/", dao.collection(shared.id)!!.url)
                    assertEquals(
                        calendars,
                        dao.collections(dao.accounts().single { !it.local }.id).size,
                    )
                    val uids = dao.eventsOf(shared.id).map { it.uid }
                    assertTrue(added in uids && kept in uids && gone !in uids, uids.toString())
                    assertTrue(dao.eventsOf(shared.id).none { it.dirty })
                    val serverKept =
                        alice.multiget(family, listOf("$family$kept.ics")).single().ics!!
                    assertTrue("SUMMARY:Kept - bob" in serverKept, serverKept)
                } finally {
                    for (uid in listOf(kept, gone, added)) {
                        assertIs<WriteResult>(alice.delete("$family$uid.ics", WriteCondition.None))
                    }
                }
            }
        }
}
