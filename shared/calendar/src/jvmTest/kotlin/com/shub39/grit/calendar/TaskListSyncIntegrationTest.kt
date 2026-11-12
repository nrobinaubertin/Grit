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
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.TaskListEntity
import com.shub39.grit.calendar.tasks.TaskListSync
import com.shub39.grit.core.tasks.Category
import com.shub39.grit.core.tasks.SubTask
import com.shub39.grit.core.tasks.Task
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.Assume.assumeTrue

/**
 * Task lists sync with `server/radicale` (seed-dev.sh): a Grit category goes to the server, a
 * second phone links it, and two users share a list. Each test makes its own lists, so that it
 * never touches the lists of the seed, which phones use for manual tests. Skipped unless
 * RADICALE_URL is set.
 */
class TaskListSyncIntegrationTest {
    private val baseUrl = System.getenv("RADICALE_URL").orEmpty().trimEnd('/')
    private val devices = mutableListOf<Device>()

    private class Device(val user: String, private val baseUrl: String) {
        val db =
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val store = CalendarStore(db)
        val repo = FakeTaskRepo()
        val taskLists = TaskListSync(db, store, repo, NoAlarms)
        val manager: CalendarSyncManager

        init {
            System.setProperty(
                "grit.calendar.dir",
                Files.createTempDirectory("grit-$user").toString(),
            )
            manager =
                CalendarSyncManager(db, store, CredentialStore(), ReminderScheduler(), taskLists)
        }

        suspend fun connect() {
            manager.addAccount(baseUrl, user, user, manager.discover(baseUrl, user, user))
            manager.syncAll()
        }

        /** By name: a list that Grit creates has a random address. */
        suspend fun list(name: String): TaskListEntity =
            db.taskListDao().listsFlow().first().single { it.displayName == name }

        fun category(name: String): Category = repo.categories.value.single { it.name == name }

        fun tasks(category: Category): Map<String, Task> =
            repo.tasks.value.filter { it.categoryId == category.id }.associateBy { it.title }

        fun subTasks(task: Task): Map<String, SubTask> =
            repo.subTasks.value.filter { it.taskId == task.id }.associateBy { it.title }

        suspend fun close() {
            taskLists.close()
            db.close()
        }
    }

    private fun device(user: String) = Device(user, baseUrl).also { devices += it }

    @AfterTest fun close() = runBlocking { devices.forEach { it.close() } }

    @Test
    fun `a category goes to the server, and a second phone gets it with changes both ways`() =
        runBlocking {
            assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
            val phone = device("alice")
            val tablet = device("alice")
            phone.connect()

            // A category with tasks, a done task, and subtasks
            phone.repo.upsertCategory(Category(name = "Trip", index = 1, color = ""))
            val trip = phone.category("Trip")
            val due = LocalDateTime(2026, 12, 1, 9, 0)
            phone.repo.upsertTask(Task(categoryId = trip.id, title = "Tickets", reminder = due))
            val packing =
                phone.repo.upsertTask(Task(categoryId = trip.id, title = "Packing", index = 1))
            phone.repo.upsertTask(Task(categoryId = trip.id, title = "Visa", status = true))
            phone.repo.upsertSubTask(SubTask(taskId = packing, title = "Socks"))
            phone.repo.upsertSubTask(SubTask(taskId = packing, title = "Charger", index = 1))

            phone.manager.startSyncingTasks(trip.id, "Trip")
            val url = phone.list("Trip").url
            val dav = KtorDavClient(baseUrl, Credentials("alice", "alice"))
            try {
                assertEquals(5, dav.listEtags(url, ComponentType.VTODO).size)
                assertNull(phone.list("Trip").lastError)

                // The second phone links the list: a new category with the same tasks
                tablet.connect()
                tablet.taskLists.link(tablet.list("Trip").id)
                tablet.manager.syncAll()
                val tabletTrip = tablet.category("Trip")
                val tasks = tablet.tasks(tabletTrip)
                assertEquals(setOf("Tickets", "Packing", "Visa"), tasks.keys)
                assertEquals(due, tasks.getValue("Tickets").reminder)
                assertTrue(tasks.getValue("Visa").status)
                assertEquals(1, tasks.getValue("Packing").index)
                assertEquals(
                    setOf("Socks", "Charger"),
                    tablet.subTasks(tasks.getValue("Packing")).keys,
                )

                // Changes on the tablet
                val tablePacking = tasks.getValue("Packing")
                tablet.repo.upsertTask(
                    tasks.getValue("Tickets").copy(status = true, reminder = null)
                )
                tablet.repo.upsertTask(tablePacking.copy(title = "Packing list"))
                tablet.repo.deleteTask(tasks.getValue("Visa"))
                tablet.repo.upsertTask(
                    Task(categoryId = tabletTrip.id, title = "Insurance", index = 3)
                )
                tablet.repo.upsertSubTask(SubTask(taskId = tablePacking.id, title = "Adapter"))
                tablet.repo.upsertSubTask(
                    tablet.subTasks(tablePacking).getValue("Socks").copy(status = true)
                )
                tablet.manager.syncAll()
                assertNull(tablet.list("Trip").lastError)

                // ... reach the phone
                phone.manager.syncAll()
                val phoneTasks = phone.tasks(trip)
                assertEquals(setOf("Tickets", "Packing list", "Insurance"), phoneTasks.keys)
                assertTrue(phoneTasks.getValue("Tickets").status)
                assertNull(phoneTasks.getValue("Tickets").reminder)
                assertEquals(3, phoneTasks.getValue("Insurance").index)
                val phoneSubTasks = phone.subTasks(phoneTasks.getValue("Packing list"))
                assertEquals(setOf("Socks", "Charger", "Adapter"), phoneSubTasks.keys)
                assertTrue(phoneSubTasks.getValue("Socks").status)
                // The server keeps the due date of the done task for other apps
                val tickets =
                    dav.multiget(url, dav.listEtags(url, ComponentType.VTODO).map { it.href })
                        .single { "SUMMARY:Tickets" in it.ics!! }
                assertTrue("DUE:20261201T090000" in tickets.ics!!, tickets.ics)
                assertEquals(6, dav.listEtags(url, ComponentType.VTODO).size)

                // Linking the same list again pairs the tasks by title: no copies
                val listId = phone.list("Trip").id
                phone.taskLists.unlink(listId)
                phone.taskLists.link(listId, trip.id)
                phone.manager.syncAll()
                assertEquals(3, phone.repo.tasks.value.count { it.categoryId == trip.id })
                assertEquals(3, phone.repo.subTasks.value.size)
                assertEquals(6, dav.listEtags(url, ComponentType.VTODO).size)

                // Deleting the category in Grit stops the sync, and the server keeps the tasks
                tablet.repo.deleteCategory(tabletTrip)
                tablet.manager.syncAll()
                assertNull(tablet.list("Trip").categoryId)
                assertEquals(6, dav.listEtags(url, ComponentType.VTODO).size)
            } finally {
                dav.delete(url, WriteCondition.None)
                dav.close()
            }
        }

    @Test
    fun `to-dos without an order value come in the order of their creation`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        val dav = KtorDavClient(baseUrl, Credentials("alice", "alice"))
        val url = "$baseUrl/alice/order-${java.util.UUID.randomUUID()}/"
        try {
            dav.createCalendar(url, "Order test", null, ComponentType.VTODO)
            // The names sort the other way round from the creation times
            for ((name, created) in
                listOf("a" to "20261110", "b" to "20261105", "c" to "20261101")) {
                val ics =
                    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VTODO\r\n" +
                        "UID:$name\r\nDTSTAMP:20261111T000000Z\r\n" +
                        "CREATED:${created}T090000Z\r\nSUMMARY:$name\r\n" +
                        "END:VTODO\r\nEND:VCALENDAR\r\n"
                dav.put("$url$name.ics", ics, WriteCondition.MustNotExist)
            }
            val phone = device("alice")
            phone.connect()
            phone.taskLists.link(phone.list("Order test").id)
            phone.manager.syncAll()
            val category = phone.category("Order test")
            assertEquals(
                listOf("c", "b", "a"),
                phone.repo.tasks.value
                    .filter { it.categoryId == category.id }
                    .sortedBy { it.index }
                    .map { it.title },
            )
        } finally {
            dav.delete(url, WriteCondition.None)
            dav.close()
        }
    }

    @Test
    fun `a removed account keeps its data, and the server at another address connects it again`() =
        runBlocking {
            assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
            // The same server under another host name, as after a move to a new domain
            val other = baseUrl.replace("127.0.0.1", "localhost")
            assumeTrue("RADICALE_URL must use 127.0.0.1", other != baseUrl)
            val id = java.util.UUID.randomUUID().toString().take(8)
            val calendarUrl = "$baseUrl/alice/move-$id/"
            val listUrl = "$baseUrl/alice/move-tasks-$id/"
            val dav = KtorDavClient(baseUrl, Credentials("alice", "alice"))
            try {
                dav.createCalendar(calendarUrl, "Move $id", null)
                dav.put(
                    "${calendarUrl}kept.ics",
                    event("kept", "Kept"),
                    WriteCondition.MustNotExist,
                )
                dav.createCalendar(listUrl, "Move tasks $id", null, ComponentType.VTODO)
                dav.put("${listUrl}bread.ics", todo("bread", "Bread"), WriteCondition.MustNotExist)

                val phone = device("alice")
                phone.connect()
                phone.taskLists.link(phone.list("Move tasks $id").id)
                phone.manager.syncAll()
                val dao = phone.db.calendarDao()
                val calendar =
                    dao.collections(dao.accounts().single { !it.local }.id).single {
                        it.displayName == "Move $id"
                    }
                val category = phone.category("Move tasks $id")
                val bread = phone.tasks(category).getValue("Bread")

                // Changes that do not reach the server before the account goes
                phone.store.createEvent(
                    calendar.id,
                    com.shub39.grit.caldav.ical.EventDraft(
                        summary = "Added before the move",
                        start =
                            com.shub39.grit.caldav.ical.IcalTime.Zoned(
                                LocalDateTime(2026, 12, 3, 10, 0),
                                "Europe/Paris",
                            ),
                        end =
                            com.shub39.grit.caldav.ical.IcalTime.Zoned(
                                LocalDateTime(2026, 12, 3, 11, 0),
                                "Europe/Paris",
                            ),
                    ),
                )
                phone.repo.upsertTask(bread.copy(title = "Bread and butter"))
                phone.taskLists.detectAll()

                phone.manager.removeAccount(dao.accounts().single { !it.local }.id)
                // Everything stays on the phone: the calendar on the device, the list detached
                assertEquals(true, dao.isLocalCollection(calendar.id))
                assertEquals(
                    setOf("Kept", "Added before the move"),
                    dao.eventsOf(calendar.id).map { it.summary }.toSet(),
                )
                assertEquals(setOf("Bread and butter"), phone.tasks(category).keys)
                assertNull(phone.list("Move tasks $id").accountId)

                // The same server at another address: both connect again, without copies
                phone.manager.addAccount(
                    other,
                    "alice",
                    "alice",
                    phone.manager.discover(other, "alice", "alice"),
                )
                phone.manager.syncAll()
                val account = dao.accounts().single { !it.local }
                val serverCalendars = dao.collections(account.id).filter { "/move-$id/" in it.url }
                assertEquals(listOf(calendar.id), serverCalendars.map { it.id })
                assertTrue(
                    serverCalendars.single().url.startsWith(other),
                    serverCalendars.toString(),
                )
                assertEquals(1, phone.repo.categories.value.count { it.name == "Move tasks $id" })
                assertEquals(setOf("Bread and butter"), phone.tasks(category).keys)
                val list = phone.list("Move tasks $id")
                assertEquals(account.id, list.accountId)
                assertTrue(list.url.startsWith(other), list.url)
                assertNull(list.lastError)

                // The changes made before the move are on the server now
                val events =
                    dav.multiget(
                        calendarUrl,
                        dav.listEtags(calendarUrl, ComponentType.VEVENT).map { it.href },
                    )
                assertEquals(2, events.size)
                assertTrue(events.any { "SUMMARY:Added before the move" in it.ics!! })
                val todos =
                    dav.multiget(
                        listUrl,
                        dav.listEtags(listUrl, ComponentType.VTODO).map { it.href },
                    )
                assertEquals(1, todos.size)
                assertTrue("SUMMARY:Bread and butter" in todos.single().ics!!, todos.single().ics)
            } finally {
                dav.delete(calendarUrl, WriteCondition.None)
                dav.delete(listUrl, WriteCondition.None)
                dav.close()
            }
        }

    private fun event(uid: String, summary: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
            "DTSTAMP:20261101T000000Z\r\nDTSTART:20261205T080000Z\r\nDTEND:20261205T090000Z\r\n" +
            "SUMMARY:$summary\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun todo(uid: String, summary: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\n" +
            "DTSTAMP:20261101T000000Z\r\nSUMMARY:$summary\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    @Test
    fun `two users share a task list, and the later change wins a conflict`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        // A new list of alice, shared with bob with write access, as seed-dev.sh does
        val name = "shared-" + java.util.UUID.randomUUID().toString().take(8)
        val url = "$baseUrl/alice/$name/"
        val dav = KtorDavClient(baseUrl, Credentials("alice", "alice"))
        try {
            dav.createCalendar(url, "Shared test", null, ComponentType.VTODO)
            dav.put("${url}bins.ics", BINS, WriteCondition.MustNotExist)
            sharing(
                "alice",
                "create",
                """{"PathOrToken": "/bob/$name/", "PathMapped": "/alice/$name/", "User": "bob", """ +
                    """"Permissions": "rw", "Enabled": true, "Hidden": false}""",
            )
            for (step in listOf("enable", "unhide")) {
                sharing("bob", step, """{"PathOrToken": "/bob/$name/"}""")
            }
            val alice = device("alice")
            val bob = device("bob")
            alice.connect()
            bob.connect()
            alice.taskLists.link(alice.list("Shared test").id)
            bob.taskLists.link(bob.list("Shared test").id)
            alice.manager.syncAll()
            bob.manager.syncAll()
            val aliceChores = alice.category("Shared test")
            val bobChores = bob.category("Shared test")
            assertEquals(setOf("Take out the bins"), alice.tasks(aliceChores).keys)
            assertEquals(setOf("Take out the bins"), bob.tasks(bobChores).keys)

            // bob adds a task, alice marks the bins as done
            bob.repo.upsertTask(Task(categoryId = bobChores.id, title = "Water the plants"))
            bob.manager.syncAll()
            val aliceBins = alice.tasks(aliceChores).getValue("Take out the bins")
            alice.repo.upsertTask(aliceBins.copy(status = true))
            alice.manager.syncAll()
            bob.manager.syncAll()
            assertTrue(alice.tasks(aliceChores).containsKey("Water the plants"))
            assertTrue(bob.tasks(bobChores).getValue("Take out the bins").status)

            // Both rename the same task. bob's change is older: alice's wins on both phones
            val bobPlants = bob.tasks(bobChores).getValue("Water the plants")
            bob.repo.upsertTask(bobPlants.copy(title = "Water the plants (bob)"))
            bob.taskLists.detectAll()
            Thread.sleep(1100)
            val alicePlants = alice.tasks(aliceChores).getValue("Water the plants")
            alice.repo.upsertTask(alicePlants.copy(title = "Water the plants (alice)"))
            bob.manager.syncAll()
            alice.manager.syncAll()
            bob.manager.syncAll()
            assertTrue(alice.tasks(aliceChores).containsKey("Water the plants (alice)"))
            assertTrue(bob.tasks(bobChores).containsKey("Water the plants (alice)"))
            assertEquals(2, bob.tasks(bobChores).size)
        } finally {
            runCatching { sharing("alice", "delete", """{"PathOrToken": "/bob/$name/"}""") }
            dav.delete(url, WriteCondition.None)
            dav.close()
        }
    }

    /** Calls the Radicale sharing API as [user], who has the same password. */
    private fun sharing(user: String, action: String, json: String) {
        val request =
            java.net.http.HttpRequest.newBuilder(java.net.URI("$baseUrl/.sharing/v1/map/$action"))
                .header("Content-Type", "application/json")
                .header(
                    "Authorization",
                    "Basic " +
                        java.util.Base64.getEncoder().encodeToString("$user:$user".toByteArray()),
                )
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build()
        val response =
            java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() == 200 && "success" in response.body(), response.body())
    }

    private companion object {
        /** The first to-do of the shared list. */
        const val BINS =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//seed//EN\r\nBEGIN:VTODO\r\n" +
                "UID:bins\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Take out the bins\r\n" +
                "STATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
    }
}
