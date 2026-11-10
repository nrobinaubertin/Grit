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
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.db.CalendarDatabase
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `a calendar moves to the server and back to the device`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        System.setProperty("grit.calendar.dir", Files.createTempDirectory("grit-toggle").toString())
        val manager = CalendarSyncManager(db, store, CredentialStore(), ReminderScheduler())
        manager.addAccount(baseUrl, "bob", "bob", manager.discover(baseUrl, "bob", "bob"))
        manager.syncAll()

        val calendar = store.createLocalCalendar("Trip notes")
        store.createEvent(
            calendar,
            EventDraft(
                summary = "Ferry",
                start = IcalTime.Zoned(LocalDateTime(2026, 12, 1, 8, 0), "Europe/Paris"),
                end = IcalTime.Zoned(LocalDateTime(2026, 12, 1, 9, 0), "Europe/Paris"),
            ),
        )
        store.createEvent(
            calendar,
            EventDraft(
                summary = "Walk",
                start = IcalTime.Zoned(LocalDateTime(2026, 12, 2, 8, 0), "Europe/Paris"),
                end = IcalTime.Zoned(LocalDateTime(2026, 12, 2, 9, 0), "Europe/Paris"),
                rrule = "FREQ=WEEKLY;COUNT=3",
            ),
        )

        manager.startSyncing(calendar)
        val synced = dao.collection(calendar)!!
        val account = dao.accounts().single { !it.local }
        assertEquals(account.id, synced.accountId)
        assertTrue(synced.url.startsWith("$baseUrl/bob/"), synced.url)
        // Uploaded: nothing waits, and every event has its server ETag
        assertTrue(dao.eventsOf(calendar).all { !it.dirty && it.etag != null })

        KtorDavClient(baseUrl, Credentials("bob", "bob")).use { dav ->
            try {
                val found = dav.discover(baseUrl).collections.single { it.url == synced.url }
                assertEquals("Trip notes", found.displayName)
                assertEquals(2, dav.listEtags(synced.url, ComponentType.VEVENT).size)

                manager.stopSyncing(calendar)
                val local = dao.collection(calendar)!!
                assertEquals(dao.localAccount()!!.id, local.accountId)
                assertTrue(local.url.startsWith(CalendarStore.LOCAL_URL_PREFIX))
                assertEquals(listOf("Ferry", "Walk"), dao.eventsOf(calendar).map { it.summary })
                assertTrue(dao.eventsOf(calendar).none { it.dirty })

                // The next sync leaves the server copy out, and the server keeps it
                manager.syncAll()
                assertFalse(dao.collections(account.id).any { it.url == synced.url })
                assertEquals(2, dav.listEtags(synced.url, ComponentType.VEVENT).size)
            } finally {
                dav.delete(synced.url, WriteCondition.None)
            }
        }
    }
}
