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
 * second phone links it, and two users share `alice/chores`. Skipped unless RADICALE_URL is set.
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
    fun `two users share a task list, and the later change wins a conflict`() = runBlocking {
        assumeTrue("RADICALE_URL is not set", baseUrl.isNotEmpty())
        val chores = "$baseUrl/alice/chores/"
        val dav = KtorDavClient(baseUrl, Credentials("alice", "alice"))
        // The seed state, also after a phone test on the same server
        suspend fun reset() {
            for (item in dav.listEtags(chores, ComponentType.VTODO)) {
                if (!item.href.endsWith("/bins.ics")) dav.delete(item.href, WriteCondition.None)
            }
            dav.put("${chores}bins.ics", SEED_BINS, WriteCondition.None)
        }
        reset()
        try {
            val alice = device("alice")
            val bob = device("bob")
            alice.connect()
            bob.connect()
            alice.taskLists.link(alice.list("chores").id)
            bob.taskLists.link(bob.list("chores").id)
            alice.manager.syncAll()
            bob.manager.syncAll()
            val aliceChores = alice.category("chores")
            val bobChores = bob.category("chores")
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
            reset()
            dav.close()
        }
    }

    private companion object {
        /** The to-do that `server/radicale/seed-dev.sh` puts in `alice/chores`. */
        const val SEED_BINS =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//seed//EN\r\nBEGIN:VTODO\r\n" +
                "UID:bins\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Take out the bins\r\n" +
                "STATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
    }
}
