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
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.tasks.TaskColorStore
import com.shub39.grit.core.tasks.Task
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TaskColorStoreTest {
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val repo = FakeTaskRepo()
    private val store = TaskColorStore(db, repo)
    private val red = TaskColorStore.PALETTE[1]
    private val blue = TaskColorStore.PALETTE[0]

    @AfterTest
    fun close() {
        // The store's background work must stop before the database closes
        runBlocking { store.close() }
        db.close()
    }

    private suspend fun colorsAre(expected: Map<Long, Int>) =
        withTimeout(5_000) { store.colors.first { it == expected } }

    @Test
    fun `a task keeps its color, and a new task gets the color chosen before it had an id`() =
        runBlocking<Unit> {
            val milk = repo.upsertTask(Task(categoryId = 1, title = "Milk"))
            store.setColor(milk, red)
            colorsAre(mapOf(milk to red))

            // The editor picks a color, then the view model saves the new task
            store.setPending(blue)
            val bread = repo.upsertTask(Task(categoryId = 1, title = "Bread"))
            store.assignPending(bread)
            colorsAre(mapOf(milk to red, bread to blue))

            // The pending color is used once only
            val eggs = repo.upsertTask(Task(categoryId = 1, title = "Eggs"))
            store.assignPending(eggs)
            store.setColor(milk, null)
            colorsAre(mapOf(bread to blue))
        }

    @Test
    fun `a deleted task loses its color, the others keep theirs`() =
        runBlocking<Unit> {
            val milk = repo.upsertTask(Task(categoryId = 1, title = "Milk"))
            val bread = repo.upsertTask(Task(categoryId = 1, title = "Bread"))
            store.setColor(milk, red)
            store.setColor(bread, blue)
            colorsAre(mapOf(milk to red, bread to blue))

            repo.deleteTask(repo.getTaskById(bread)!!)
            colorsAre(mapOf(milk to red))
        }

    @Test
    fun `colors survive the kv format`() {
        assertEquals(
            mapOf(4L to red, 9L to blue),
            TaskColorStore.parse(
                mapOf(
                    TaskColorStore.PREFIX + 4 to (red.toLong() and 0xFFFFFFFFL),
                    TaskColorStore.PREFIX + 9 to (blue.toLong() and 0xFFFFFFFFL),
                    TaskColorStore.PREFIX + "x" to 1L,
                )
            ),
        )
    }
}
