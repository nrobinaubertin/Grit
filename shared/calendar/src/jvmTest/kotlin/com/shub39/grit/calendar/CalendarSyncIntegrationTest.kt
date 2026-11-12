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
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.db.CalendarDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/** Two users sync through `server/radicale/run-dev.sh`. Skipped unless RADICALE_URL is set. */
class CalendarSyncIntegrationTest {
    private val baseUrl = System.getenv("RADICALE_URL").orEmpty().trimEnd('/')

    private class Device(user: String, private val baseUrl: String) {
        val db =
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val store = CalendarStore(db)
        val manager: CalendarSyncManager
        val user = user

        init {
            System.setProperty(
                "grit.calendar.dir",
                Files.createTempDirectory("grit-$user").toString(),
            )
            manager =
                CalendarSyncManager(
                    db,
                    store,
                    CredentialStore(),
                    ReminderScheduler(),
                    com.shub39.grit.calendar.tasks.TaskListSync(db, store, FakeTaskRepo(), NoAlarms),
                )
        }

        suspend fun connect() {
            val discovery = manager.discover(baseUrl, user, user)
            manager.addAccount(baseUrl, user, user, discovery)
            manager.syncAll()
        }

        suspend fun collection(name: String) =
            db.calendarDao().collectionsFlow().first().single {
                it.url.trimEnd('/').endsWith("/$name")
            }

        suspend fun titles(): List<String?> {
            val now = Clock.System.now()
            return store
                .instances(
                    (now - 30.days).toEpochMilliseconds(),
                    (now + 30.days).toEpochMilliseconds(),
                )
                .first()
                .map { it.title }
        }
    }

    @Test
    fun `two devices share a calendar through Radicale`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        val alice = Device("alice", baseUrl)
        val bob = Device("bob", baseUrl)
        try {
            alice.connect()
            bob.connect()
            assertNull(alice.db.calendarDao().accounts().single().lastError)
            assertNull(bob.db.calendarDao().accounts().single().lastError)
            assertTrue("Dentist" in bob.titles(), "bob sees alice's shared personal calendar")
            assertEquals(false, bob.collection("alice-personal").canWrite)
            assertEquals(true, bob.collection("family").canWrite)

            val summary = "Picnic ${System.nanoTime()}"
            val start = Clock.System.now() + 2.days
            val id =
                alice.store.createEvent(
                    alice.collection("family").id,
                    EventDraft(
                        summary = summary,
                        start = IcalTime.Utc(start),
                        end = IcalTime.Utc(start + kotlin.time.Duration.parse("2h")),
                    ),
                )
            alice.manager.syncAll()
            bob.manager.syncAll()
            assertTrue(summary in bob.titles(), "bob sees the event alice created")

            alice.store.deleteEvent(id)
            alice.manager.syncAll()
            bob.manager.syncAll()
            assertTrue(summary !in bob.titles(), "bob no longer sees the deleted event")
        } finally {
            alice.db.close()
            bob.db.close()
        }
    }
}
