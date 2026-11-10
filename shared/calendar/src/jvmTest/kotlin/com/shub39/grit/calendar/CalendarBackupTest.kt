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
import com.shub39.grit.calendar.data.CalendarBackup
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.SyncScheduler
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CollectionEntity
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class CalendarBackupTest {
    init {
        // The desktop credential store must not touch the real Grit folder
        System.setProperty(
            "grit.calendar.dir",
            Files.createTempDirectory("grit-backup-test").toString(),
        )
    }

    private val databases = mutableListOf<CalendarDatabase>()

    private class Device(val db: CalendarDatabase) {
        val store = CalendarStore(db)
        val dao = db.calendarDao()
        private val reminders = ReminderScheduler()
        private val manager = CalendarSyncManager(db, store, CredentialStore(), reminders)
        val backup = CalendarBackup(db, store, reminders, SyncScheduler(manager, store))
    }

    private fun device(): Device =
        Device(
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
                .also { databases += it }
        )

    @AfterTest fun close() = databases.forEach { it.close() }

    /** Through text, like a real backup file. */
    private suspend fun backupFile(device: Device): JsonElement =
        Json.parseToJsonElement(Json.encodeToString(device.backup.export()))

    private suspend fun filled(): Device {
        val phone = device()
        val personal = phone.store.createLocalCalendar("Personal")
        phone.store.createCategory("Work", "teal")
        phone.store.createEvent(
            personal,
            EventDraft(
                summary = "Dentist",
                start = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 9, 0), "Europe/Paris"),
                end = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 10, 0), "Europe/Paris"),
                reminderMinutes = listOf(30),
            ),
        )
        phone.store.createEvent(
            personal,
            EventDraft(
                summary = "Choir",
                start = IcalTime.Zoned(LocalDateTime(2026, 11, 12, 19, 0), "Europe/Paris"),
                end = IcalTime.Zoned(LocalDateTime(2026, 11, 12, 20, 30), "Europe/Paris"),
                rrule = "FREQ=WEEKLY",
                category = "Family",
                color = "crimson",
            ),
        )
        val account =
            phone.dao.upsertAccount(AccountEntity(baseUrl = "https://dav", username = "bob"))
        phone.dao.insertCollection(
            CollectionEntity(
                accountId = account,
                url = "https://dav/bob/work/",
                displayName = "work",
            )
        )
        phone.dao.insertCollection(
            CollectionEntity(
                accountId = account,
                url = "https://dav/shared/family/",
                displayName = "family",
                manual = true,
                visible = false,
            )
        )
        return phone
    }

    private suspend fun titles(device: Device, from: String, to: String): List<String?> =
        device.store
            .instances(
                Instant.parse(from).toEpochMilliseconds(),
                Instant.parse(to).toEpochMilliseconds(),
            )
            .first()
            .map { it.title }
            .sortedBy { it }

    @Test
    fun `a restore brings back calendars, events, categories and the account`() = runBlocking {
        val old = filled()
        val file = backupFile(old)

        val new = device()
        new.backup.restore(file)

        assertEquals(
            listOf(CategoryEntity("Family", "crimson"), CategoryEntity("Work", "teal")),
            new.dao.categoriesFlow().first().sortedBy { it.name },
        )
        val oldLocal = old.dao.collections(old.dao.localAccount()!!.id).single()
        val newLocal = new.dao.collections(new.dao.localAccount()!!.id).single()
        assertEquals(oldLocal.url, newLocal.url)
        assertEquals("Personal", newLocal.displayName)
        assertEquals(
            old.dao.eventsOf(oldLocal.id).map { it.href to it.ics },
            new.dao.eventsOf(newLocal.id).map { it.href to it.ics },
        )
        assertTrue(new.dao.eventsOf(newLocal.id).none { it.dirty })
        // Occurrences, including the repeating ones, and the reminder are ready
        assertEquals(
            listOf("Choir", "Choir", "Dentist"),
            titles(new, "2026-11-12T00:00:00Z", "2026-11-21T00:00:00Z"),
        )
        assertEquals(1, new.dao.alarmsBetween(0, Long.MAX_VALUE).size)

        val account = new.dao.accounts().single { !it.local }
        assertEquals("https://dav" to "bob", account.baseUrl to account.username)
        val calendars = new.dao.collections(account.id).associateBy { it.url }
        assertEquals(setOf("https://dav/bob/work/", "https://dav/shared/family/"), calendars.keys)
        assertTrue(calendars.getValue("https://dav/shared/family/").manual)
        assertFalse(calendars.getValue("https://dav/shared/family/").visible)
        // Read-only until the first sync reads the real access rights
        assertTrue(calendars.values.none { it.canWrite })
    }

    @Test
    fun `restoring twice adds nothing, and another account stays as it is`() = runBlocking {
        val file = backupFile(filled())
        val new = device()
        val alice =
            new.dao.upsertAccount(AccountEntity(baseUrl = "https://other", username = "alice"))
        new.backup.restore(file)
        new.backup.restore(file)

        val local = new.dao.collections(new.dao.localAccount()!!.id).single()
        assertEquals(2, new.dao.eventsOf(local.id).size)
        assertEquals(listOf(alice), new.dao.accounts().filter { !it.local }.map { it.id })
        assertTrue(new.dao.collections(alice).isEmpty())
    }

    @Test
    fun `a device without calendars exports an empty part`() = runBlocking {
        val empty = device()
        val file = backupFile(empty)
        val other = device()
        other.backup.restore(file)
        assertTrue(other.dao.accounts().isEmpty())
        assertTrue(other.dao.categoriesFlow().first().isEmpty())
    }
}
