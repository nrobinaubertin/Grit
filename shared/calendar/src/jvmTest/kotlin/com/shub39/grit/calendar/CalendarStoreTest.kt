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
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.EventCollection
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CollectionEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime

class CalendarStoreTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val dao = db.calendarDao()
    private val store =
        CalendarStore(db).also {
            it.clock =
                object : Clock {
                    override fun now() = now
                }
        }

    @AfterTest fun close() = db.close()

    private suspend fun collection(): Long {
        val account = dao.upsertAccount(AccountEntity(baseUrl = "https://dav", username = "u"))
        return dao.insertCollection(
            CollectionEntity(accountId = account, url = "https://dav/u/cal/", displayName = "Cal")
        )
    }

    @Test
    fun `a category shows its calendars, renames in writable events, and exports`() = runBlocking {
        val writable = collection()
        val account = dao.accounts().single().id
        val shared =
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/u/shared/",
                    displayName = "Shared",
                    canWrite = false,
                )
            )
        store.applyRemote(
            writable,
            listOf(
                RemoteItem(
                    "https://dav/u/cal/run.ics",
                    "\"1\"",
                    ics(
                        "run",
                        "DTSTART:20261110T100000Z\nDTEND:20261110T110000Z\nSUMMARY:Run\nCATEGORIES:Sport",
                    ),
                )
            ),
        )
        store.applyRemote(
            shared,
            listOf(
                RemoteItem(
                    "https://dav/u/shared/swim.ics",
                    "\"1\"",
                    ics(
                        "swim",
                        "DTSTART:20261111T100000Z\nDTEND:20261111T110000Z\nSUMMARY:Swim\nCATEGORIES:Sport",
                    ),
                )
            ),
        )
        val uses = store.categoryUsesFlow().first().getValue("Sport")
        assertEquals(listOf("Cal" to 1, "Shared" to 1), uses.map { it.calendar to it.events })

        store.renameCategory("Sport", "Training", "teal")
        val run = dao.eventsOf(writable).single()
        assertEquals("Training", run.category)
        assertTrue("CATEGORIES:Training" in run.ics, run.ics)
        assertTrue(run.dirty)
        // The read-only calendar keeps the former name, and so does its category
        assertEquals("Sport", dao.eventsOf(shared).single().category)
        val after = store.categoryUsesFlow().first()
        assertEquals(listOf("Cal"), after.getValue("Training").map { it.calendar })
        assertEquals(listOf("Shared"), after.getValue("Sport").map { it.calendar })
        assertEquals(
            setOf("Sport", "Training"),
            dao.categoriesFlow().first().map { it.name }.toSet(),
        )

        val file = store.exportIcs(writable)
        assertTrue("X-WR-CALNAME:Cal\r\n" in file, file)
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(file).count(), file)
        assertTrue("SUMMARY:Run" in file && "SUMMARY:Swim" !in file, file)
    }

    @Test
    fun `calendars get different patterns by order, and keep a chosen one`() = runBlocking {
        val first = collection()
        val account = dao.accounts().single().id
        val second =
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/u/two/",
                    displayName = "Two",
                )
            )
        val tasksOnly =
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/u/todo/",
                    supportsEvents = false,
                    supportsTasks = true,
                )
            )
        val defaults = store.patternsFlow().first()
        assertEquals(
            mapOf(
                first to com.shub39.grit.calendar.data.CalendarPattern.Solid,
                second to com.shub39.grit.calendar.data.CalendarPattern.Outline,
            ),
            defaults,
        )
        assertFalse(tasksOnly in defaults)

        store.setPattern(first, com.shub39.grit.calendar.data.CalendarPattern.Crossed)
        assertEquals(
            com.shub39.grit.calendar.data.CalendarPattern.Crossed,
            store.patternsFlow().first().getValue(first),
        )
    }

    private fun ics(uid: String, body: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
            "DTSTAMP:20260101T000000Z\r\n" +
            body.trimIndent().replace("\n", "\r\n") +
            "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private suspend fun instances(from: String, to: String) =
        store
            .instances(
                Instant.parse(from).toEpochMilliseconds(),
                Instant.parse(to).toEpochMilliseconds(),
            )
            .first()

    @Test
    fun `server events are expanded into instances and alarms`() = runBlocking {
        val collectionId = collection()
        store.applyRemote(
            collectionId,
            listOf(
                RemoteItem(
                    "https://dav/u/cal/weekly.ics",
                    "\"1\"",
                    ics(
                        "weekly",
                        """
                        DTSTART:20261102T080000Z
                        DTEND:20261102T090000Z
                        RRULE:FREQ=WEEKLY
                        SUMMARY:Weekly
                        BEGIN:VALARM
                        ACTION:DISPLAY
                        TRIGGER:-PT10M
                        DESCRIPTION:x
                        END:VALARM
                        """,
                    ),
                ),
                RemoteItem(
                    "https://dav/u/cal/old.ics",
                    "\"2\"",
                    ics("old", "DTSTART;VALUE=DATE:20200101\nSUMMARY:Long ago"),
                ),
            ),
        )
        val november = instances("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z")
        assertEquals(5, november.size)
        assertTrue(november.all { it.title == "Weekly" && it.recurring && it.canWrite })
        // Single events are never cut by the expansion window
        assertEquals(1, instances("2019-12-31T00:00:00Z", "2020-01-02T00:00:00Z").size)
        // Repeating events stop at the window end until it grows
        assertTrue(instances("2028-06-01T00:00:00Z", "2028-07-01T00:00:00Z").isEmpty())
        store.ensureWindow(
            Instant.parse("2028-06-01T00:00:00Z").toEpochMilliseconds(),
            Instant.parse("2028-07-01T00:00:00Z").toEpochMilliseconds(),
        )
        assertEquals(4, instances("2028-06-01T00:00:00Z", "2028-07-01T00:00:00Z").size)

        val next = dao.nextAlarm(now.toEpochMilliseconds())!!
        assertEquals(Instant.parse("2026-11-16T07:50:00Z").toEpochMilliseconds(), next.triggerAt)
        assertEquals("Weekly", next.title)
    }

    @Test
    fun `local edits are pending until uploaded`() = runBlocking {
        val collectionId = collection()
        val local = EventCollection(store, dao, collectionId, "https://dav/u/cal/")
        val id =
            store.createEvent(
                collectionId,
                EventDraft(
                    summary = "Dentist",
                    start = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 9, 0), "Europe/Paris"),
                    end = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 10, 0), "Europe/Paris"),
                ),
            )
        val pending = local.pendingChanges().single()
        assertEquals(id, pending.id)
        assertFalse(pending.existsOnServer)
        assertTrue(pending.href.startsWith("https://dav/u/cal/") && pending.href.endsWith(".ics"))
        assertEquals(
            "Dentist",
            instances("2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z").single().title,
        )

        // An edit during the upload keeps the event pending
        store.updateEvent(
            id,
            EventDraft(
                summary = "Dentist (moved)",
                start = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 11, 0), "Europe/Paris"),
                end = IcalTime.Zoned(LocalDateTime(2026, 11, 20, 12, 0), "Europe/Paris"),
            ),
        )
        local.markUploaded(id, pending.version, "\"e1\"")
        val stillPending = local.pendingChanges().single()
        assertTrue(stillPending.existsOnServer)
        assertEquals("\"e1\"", stillPending.etag)
        local.markUploaded(id, stillPending.version, "\"e2\"")
        assertTrue(local.pendingChanges().isEmpty())
        assertEquals(mapOf(pending.href to "\"e2\""), local.knownEtags())
    }

    @Test
    fun `calendars on this device keep events without pending uploads`() = runBlocking {
        val calendar = store.createLocalCalendar("Personal")
        assertEquals(true, dao.isLocalCollection(calendar))
        // A second calendar reuses the same local account
        store.createLocalCalendar("Work")
        assertEquals(1, dao.accounts().count { it.local })

        val id =
            store.createEvent(
                calendar,
                EventDraft(
                    summary = "Haircut",
                    start = IcalTime.Utc(Instant.parse("2026-11-21T10:00:00Z")),
                    end = IcalTime.Utc(Instant.parse("2026-11-21T11:00:00Z")),
                ),
            )
        val local = EventCollection(store, dao, calendar, "local:x/")
        assertTrue(local.pendingChanges().isEmpty())
        assertEquals(0, dao.pendingCountFlow().first())
        store.updateEvent(
            id,
            EventDraft(
                summary = "Haircut (moved)",
                start = IcalTime.Utc(Instant.parse("2026-11-22T10:00:00Z")),
                end = IcalTime.Utc(Instant.parse("2026-11-22T11:00:00Z")),
            ),
        )
        assertTrue(local.pendingChanges().isEmpty())
        assertEquals(
            "Haircut (moved)",
            instances("2026-11-22T00:00:00Z", "2026-11-23T00:00:00Z").single().title,
        )

        store.deleteEvent(id)
        assertEquals(null, dao.event(id))

        store.createEvent(
            calendar,
            EventDraft(
                summary = "Kept until the calendar goes",
                start = IcalTime.Utc(Instant.parse("2026-11-23T10:00:00Z")),
                end = IcalTime.Utc(Instant.parse("2026-11-23T11:00:00Z")),
            ),
        )
        store.deleteLocalCalendar(calendar)
        assertTrue(instances("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z").isEmpty())
    }

    @Test
    fun `categories come from events and their color goes back into the events`() = runBlocking {
        val collectionId = collection()
        store.applyRemote(
            collectionId,
            listOf(
                RemoteItem(
                    "https://dav/u/cal/a.ics",
                    "\"1\"",
                    ics("a", "DTSTART:20261110T080000Z\nSUMMARY:A\nCATEGORIES:Family\nCOLOR:teal"),
                ),
                RemoteItem(
                    "https://dav/u/cal/b.ics",
                    "\"2\"",
                    ics("b", "DTSTART:20261111T080000Z\nSUMMARY:B\nCATEGORIES:Sport"),
                ),
            ),
        )
        val categories = dao.categoriesFlow().first().associate { it.name to it.color }
        assertEquals("teal", categories["Family"])
        // A category without a color gets a stable one from the palette
        assertTrue(
            categories.getValue("Sport") in com.shub39.grit.calendar.data.CategoryColors.palette
        )
        val row = instances("2026-11-10T00:00:00Z", "2026-11-11T00:00:00Z").single()
        assertEquals("Family", row.category)
        assertEquals("teal", row.categoryColor)

        store.setCategoryColor("Family", "crimson")
        val changed = dao.eventByHref(collectionId, "https://dav/u/cal/a.ics")!!
        assertTrue(changed.dirty)
        assertTrue("COLOR:crimson" in changed.ics)
        assertEquals(
            "crimson",
            instances("2026-11-10T00:00:00Z", "2026-11-11T00:00:00Z").single().categoryColor,
        )

        store.deleteCategory("Sport")
        val cleared = dao.eventByHref(collectionId, "https://dav/u/cal/b.ics")!!
        assertEquals(null, cleared.category)
        assertTrue("CATEGORIES" !in cleared.ics)
    }

    @Test
    fun `deleting one occurrence removes only that instance`() = runBlocking {
        val collectionId = collection()
        val id =
            store.createEvent(
                collectionId,
                EventDraft(
                    summary = "Gym",
                    start = IcalTime.Utc(Instant.parse("2026-11-10T18:00:00Z")),
                    end = IcalTime.Utc(Instant.parse("2026-11-10T19:00:00Z")),
                    rrule = "FREQ=DAILY;COUNT=3",
                ),
            )
        store.deleteOccurrence(id, Instant.parse("2026-11-11T18:00:00Z").toEpochMilliseconds())
        val days =
            instances("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z").map {
                Instant.fromEpochMilliseconds(it.begin).toString()
            }
        assertEquals(listOf("2026-11-10T18:00:00Z", "2026-11-12T18:00:00Z"), days)

        store.deleteEvent(id)
        assertTrue(instances("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z").isEmpty())
    }
}
