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

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.CredentialStore
import com.shub39.grit.calendar.data.ReminderScheduler
import com.shub39.grit.calendar.data.SyncScheduler
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.tasks.TaskColorStore
import com.shub39.grit.calendar.tasks.TaskListSync
import com.shub39.grit.calendar.ui.home.CalendarAction
import com.shub39.grit.calendar.ui.home.CalendarUiState
import com.shub39.grit.calendar.ui.home.CalendarViewModel
import com.shub39.grit.calendar.ui.home.DayEvent
import com.shub39.grit.calendar.ui.home.TASK_MARK
import com.shub39.grit.calendar.ui.home.toDayEvent
import com.shub39.grit.core.tasks.Task
import com.shub39.grit.core.tasks.TasksStatusHandler
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Open tasks with a reminder show in the calendar views, and leave them when they are done. */
class CalendarTasksTest {
    private val db =
        Room.inMemoryDatabaseBuilder<CalendarDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val repo = FakeTaskRepo()
    private val colors = TaskColorStore(db, repo)
    private val viewModels = ViewModelStore()
    private val today =
        java.time.LocalDate.now().let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }

    @AfterTest
    fun close() {
        viewModels.clear()
        runBlocking { colors.close() }
        db.close()
    }

    private fun viewModel(): CalendarViewModel {
        System.setProperty("grit.calendar.dir", Files.createTempDirectory("grit-tasks").toString())
        val store = CalendarStore(db)
        val manager =
            CalendarSyncManager(
                db,
                store,
                CredentialStore(),
                ReminderScheduler(),
                TaskListSync(db, store, repo, NoAlarms),
            )
        // Through a store, so that the test can clear the view model and stop its flows
        val factory = viewModelFactory {
            initializer {
                CalendarViewModel(
                    store,
                    manager,
                    SyncScheduler(manager, store),
                    colors,
                    FakeSettings,
                    repo,
                    TasksStatusHandler(repo, NoAlarms),
                )
            }
        }
        return ViewModelProvider.create(viewModels, factory)[CalendarViewModel::class]
    }

    private suspend fun CalendarViewModel.until(test: (CalendarUiState) -> Boolean) =
        withTimeout(5_000) { state.first { it.ready && test(it) } }

    private fun CalendarUiState.todayTasks(): List<DayEvent> =
        monthData.byDay[today].orEmpty().filter { it.taskId != null }

    @Test
    fun `a task with a date shows on its day, the filter hides it, and done removes it`() =
        runBlocking<Unit> {
            val rent =
                repo.upsertTask(
                    Task(categoryId = 1, title = "Pay rent", reminder = today.atTime(18, 0))
                )
            // Done, or without a date: not in the calendar
            repo.upsertTask(
                Task(categoryId = 1, title = "Old", status = true, reminder = today.atTime(9, 0))
            )
            repo.upsertTask(Task(categoryId = 1, title = "Someday"))
            colors.setColor(rent, TaskColorStore.PALETTE[1])
            val viewModel = viewModel()

            val shown =
                viewModel.until { s ->
                    s.todayTasks().any { it.color == TaskColorStore.PALETTE[1] }
                }
            val entry = shown.todayTasks().single()
            assertEquals(TASK_MARK + "Pay rent", entry.title)
            assertEquals("Inbox", entry.category)
            assertEquals("t$rent", entry.key)
            assertTrue(!entry.canWrite)
            // No calendar exists, but the views show for the task
            assertTrue(shown.hasCalendars)
            assertTrue(shown.calendars.single().tasks)
            // The week and the agenda show it too
            assertTrue(shown.agenda.any { (_, events) -> events.any { it.taskId == rent } })
            assertTrue(
                shown.weeks.values.any { week -> week.boxes.any { it.event.taskId == rent } }
            )

            viewModel.onAction(CalendarAction.SetTasksVisible(false))
            viewModel.until { s ->
                s.calendars.singleOrNull()?.visible == false && s.todayTasks().isEmpty()
            }

            viewModel.onAction(CalendarAction.SetTasksVisible(true))
            viewModel.until { s -> s.todayTasks().isNotEmpty() }
            viewModel.onAction(CalendarAction.CompleteTask(rent))
            // The filter and the month update separately: wait for both
            val done = viewModel.until { s -> s.calendars.isEmpty() && s.todayTasks().isEmpty() }
            assertTrue(!done.hasCalendars)
            val task = repo.getTaskById(rent)!!
            assertTrue(task.status)
            assertNull(task.reminder)
        }

    @Test
    fun `a task late in the evening ends at midnight, on its own day`() {
        val zone = ZoneId.of("Europe/Paris")
        val task =
            Task(
                id = 7,
                categoryId = 1,
                title = "Bins",
                reminder = LocalDateTime(2026, 11, 12, 23, 45),
            )
        val entry = task.toDayEvent("Home", null, zone)
        val midnight =
            java.time.LocalDate.of(2026, 11, 13).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(midnight - 15 * 60_000L, entry.begin)
        assertEquals(midnight, entry.end)
        assertEquals(LocalDate(2026, 11, 12), entry.startDay)
        assertEquals(LocalDate(2026, 11, 12), entry.endDay)
        assertEquals(-7L, entry.instanceId)
    }

    private fun LocalDate.atTime(hour: Int, minute: Int) =
        LocalDateTime(this, LocalTime(hour, minute))
}
