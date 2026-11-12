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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.ui.editor.EventEditorScreen
import com.shub39.grit.calendar.ui.editor.EventEditorViewModel
import com.shub39.grit.calendar.ui.home.CalendarHomeScreen
import com.shub39.grit.calendar.ui.home.CalendarMode
import com.shub39.grit.calendar.ui.home.CalendarUiState
import com.shub39.grit.calendar.ui.home.DayEvent
import com.shub39.grit.calendar.ui.home.EventDetailsUi
import com.shub39.grit.calendar.ui.home.MONTH_LANES
import com.shub39.grit.calendar.ui.home.MonthData
import com.shub39.grit.calendar.ui.home.WEEK_BAND_LANES
import com.shub39.grit.calendar.ui.home.WeekData
import com.shub39.grit.calendar.ui.home.WeekLanes
import com.shub39.grit.calendar.ui.home.byDay
import com.shub39.grit.calendar.ui.home.isBandEvent
import com.shub39.grit.calendar.ui.home.layoutTimed
import com.shub39.grit.calendar.ui.home.layoutWeek
import com.shub39.grit.calendar.ui.home.startOfWeek
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.junit.Assume.assumeTrue

/**
 * Renders the screens off-screen to PNG files, for a visual check without a device. Runs only when
 * GRIT_SCREENSHOTS names an output folder.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val output = System.getenv("GRIT_SCREENSHOTS")?.let(::File)

    private fun shoot(
        name: String,
        width: Int = 412,
        height: Int = 900,
        root: Int = 0,
        ready: () -> Boolean = { true },
        content: @Composable () -> Unit,
    ) {
        assumeTrue("GRIT_SCREENSHOTS is not set", output != null)
        output!!.mkdirs()
        runDesktopComposeUiTest(width, height) {
            setContent { MaterialTheme(colorScheme = darkColorScheme()) { content() } }
            waitUntil(timeoutMillis = 5_000) { ready() }
            waitForIdle()
            // A bottom sheet or dialog is a second root
            val image = onAllNodes(isRoot())[root].captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(output, "$name.png"))
        }
    }

    private val today = LocalDate(2026, 11, 9)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDate(2026, 11, day)
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds() + (hour * 60 + minute) * 60_000L

    private var nextId = 1L

    private fun event(
        title: String,
        day: Int,
        hour: Int,
        hours: Int = 1,
        category: String? = null,
        color: Int? = null,
        allDayUntil: Int? = null,
        canWrite: Boolean = true,
    ): DayEvent {
        val id = nextId++
        val allDay = allDayUntil != null
        val begin =
            if (allDay) LocalDate(2026, 11, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            else at(day, hour)
        val end =
            if (allDay)
                LocalDate(2026, 11, allDayUntil!!)
                    .plus(DatePeriod(days = 1))
                    .atStartOfDayIn(TimeZone.UTC)
                    .toEpochMilliseconds()
            else at(day, hour + hours)
        return DayEvent(
            instanceId = id,
            eventId = id,
            title = title,
            location = if (id % 2 == 0L) "Home" else null,
            begin = begin,
            end = end,
            allDay = allDay,
            startDay = LocalDate(2026, 11, day),
            endDay = LocalDate(2026, 11, allDayUntil ?: day),
            originalStart = null,
            recurring = title == "Standup",
            category = category,
            color = color,
            calendarName = if (canWrite) "family" else "alice-personal",
            canWrite = canWrite,
        )
    }

    private val pink = 0xFFE91E63.toInt()
    private val indigo = 0xFF3F51B5.toInt()
    private val teal = 0xFF009688.toInt()

    private val events =
        listOf(
            event("Dentist", 9, 9, category = "Health", color = indigo, canWrite = false),
            event("Groceries", 9, 18, category = "Family", color = pink),
            event("Standup", 10, 9, category = "Work", color = teal),
            event("Standup", 11, 9, category = "Work", color = teal),
            event("Review", 11, 9, hours = 2, category = "Work", color = teal),
            event("Standup", 12, 9, category = "Work", color = teal),
            event("Conference in Lyon", 11, 0, allDayUntil = 13, category = "Work", color = teal),
            event("Dinner", 20, 19, category = "Family", color = pink),
            event("Film", 20, 21),
            event("Holidays", 23, 0, allDayUntil = 28, category = "Family", color = pink),
            event("Breakfast with Paul", 24, 8, category = "Family", color = pink),
            event("Train to Lyon", 24, 10, category = "Work", color = teal),
            event("Museum", 24, 14),
            event("Dinner", 24, 20, category = "Family", color = pink),
        )

    private val sample: CalendarUiState
        get() {
            val first = LocalDate(2026, 9, 24)
            val last = LocalDate(2027, 1, 8)
            val weeks = mutableMapOf<LocalDate, WeekLanes>()
            var week = startOfWeek(first, DayOfWeek.MONDAY)
            while (week < last) {
                weeks[week] = layoutWeek(week, events, MONTH_LANES)
                week = week.plus(DatePeriod(days = 7))
            }
            val weekStart = startOfWeek(today, DayOfWeek.MONDAY)
            return CalendarUiState(
                month = YearMonth(2026, 11),
                week = weekStart,
                selectedDate = today,
                today = today,
                monthData = MonthData(byDay(events, first, last), weeks),
                weeks =
                    mapOf(
                        weekStart to
                            WeekData(
                                band =
                                    layoutWeek(
                                        weekStart,
                                        events.filter { it.isBandEvent() },
                                        WEEK_BAND_LANES,
                                    ),
                                boxes =
                                    layoutTimed(weekStart, events, java.time.ZoneId.systemDefault()),
                            )
                    ),
                agenda = byDay(events, today, today.plus(DatePeriod(days = 30))).toList(),
                canCreate = true,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
        }

    @Test
    fun month() =
        shoot("calendar-month") { CalendarHomeScreen(sample, {}, {}, {}, { _, _ -> }, {}) }

    @Test
    fun week() =
        shoot("calendar-week") {
            CalendarHomeScreen(sample.copy(mode = CalendarMode.Week), {}, {}, {}, { _, _ -> }, {})
        }

    @Test
    fun agenda() =
        shoot("calendar-agenda") {
            CalendarHomeScreen(sample.copy(mode = CalendarMode.Agenda), {}, {}, {}, { _, _ -> }, {})
        }

    @Test
    fun noCalendars() =
        shoot("calendar-empty") {
            CalendarHomeScreen(
                sample.copy(hasCalendars = false, canCreate = false),
                {},
                {},
                {},
                { _, _ -> },
                {},
            )
        }

    @Test
    fun detailSheet() =
        shoot("calendar-detail", root = 1) {
            CalendarHomeScreen(
                sample.copy(
                    details =
                        EventDetailsUi(
                            event = events[2],
                            description = "Daily sync with the team",
                            rrule = "FREQ=WEEKLY",
                        )
                ),
                {},
                {},
                {},
                { _, _ -> },
                {},
            )
        }

    @Test
    fun editor() {
        val db =
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        runBlocking {
            val account =
                db.calendarDao()
                    .upsertAccount(AccountEntity(baseUrl = "https://dav", username = "bob"))
            db.calendarDao()
                .insertCollection(
                    CollectionEntity(
                        accountId = account,
                        url = "https://dav/bob/family/",
                        displayName = "family",
                    )
                )
        }
        val viewModel = EventEditorViewModel(CalendarStore(db), FakeSettings)
        viewModel.load(null, today)
        shoot("calendar-editor", ready = { !viewModel.state.value.loading }) {
            val state by viewModel.state.collectAsState()
            EventEditorScreen(state, viewModel, onClose = {})
        }
        db.close()
    }

    @Test
    fun account() {
        System.setProperty(
            "grit.calendar.dir",
            java.nio.file.Files.createTempDirectory("shots").toString(),
        )
        val db =
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        runBlocking {
            val dao = db.calendarDao()
            val account =
                dao.upsertAccount(
                    AccountEntity(
                        baseUrl = "https://dav.example.com",
                        username = "bob",
                        lastSync = at(9, 12),
                    )
                )
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/bob/work/",
                    displayName = "work",
                )
            )
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/bob/family/",
                    displayName = "family",
                )
            )
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/bob/alice-personal/",
                    displayName = "alice-personal",
                    color = indigo,
                    canWrite = false,
                )
            )
            // Task lists: one syncs with the category "chores", one does not sync
            val lists = db.taskListDao()
            lists.insertList(
                com.shub39.grit.calendar.data.db.TaskListEntity(
                    accountId = account,
                    url = "https://dav/bob/chores/",
                    displayName = "chores",
                    categoryId = 2,
                    paired = true,
                )
            )
            lists.insertList(
                com.shub39.grit.calendar.data.db.TaskListEntity(
                    accountId = account,
                    url = "https://dav/bob/groceries/",
                    displayName = "groceries",
                )
            )
        }
        val store = CalendarStore(db)
        runBlocking {
            store.createLocalCalendar("Personal")
            store.createCategory("Family", "crimson")
            store.createCategory("Work", "teal")
        }
        val taskRepo = FakeTaskRepo()
        runBlocking {
            taskRepo.upsertCategory(
                com.shub39.grit.core.tasks.Category(name = "chores", index = 1, color = "")
            )
        }
        val taskLists = com.shub39.grit.calendar.tasks.TaskListSync(db, store, taskRepo, NoAlarms)
        val manager =
            com.shub39.grit.calendar.data.CalendarSyncManager(
                db,
                store,
                com.shub39.grit.calendar.data.CredentialStore(),
                com.shub39.grit.calendar.data.ReminderScheduler(),
                taskLists,
            )
        val viewModel =
            com.shub39.grit.calendar.ui.account.AccountViewModel(
                store,
                manager,
                com.shub39.grit.calendar.data.PhoneCalendars(
                    com.shub39.grit.calendar.data.PhoneCalendarSource()
                ),
                taskLists,
                taskRepo,
            )
        shoot(
            "calendar-account",
            ready = {
                viewModel.state.value.collections.size == 3 &&
                    viewModel.state.value.localCalendars.size == 1
            },
        ) {
            val state by viewModel.state.collectAsState()
            com.shub39.grit.calendar.ui.account.AccountScreen(
                state,
                viewModel,
                onBack = {},
                onOpenSync = {},
            )
        }
        db.close()
    }

    @Test
    fun accountLocalOnly() {
        System.setProperty(
            "grit.calendar.dir",
            java.nio.file.Files.createTempDirectory("shots").toString(),
        )
        val db =
            Room.inMemoryDatabaseBuilder<CalendarDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val store = CalendarStore(db)
        runBlocking {
            store.createLocalCalendar("Personal")
            store.createCategory("Family", "crimson")
            store.createCategory("Work", "teal")
        }
        val taskRepo = FakeTaskRepo()
        val taskLists = com.shub39.grit.calendar.tasks.TaskListSync(db, store, taskRepo, NoAlarms)
        val manager =
            com.shub39.grit.calendar.data.CalendarSyncManager(
                db,
                store,
                com.shub39.grit.calendar.data.CredentialStore(),
                com.shub39.grit.calendar.data.ReminderScheduler(),
                taskLists,
            )
        val viewModel =
            com.shub39.grit.calendar.ui.account.AccountViewModel(
                store,
                manager,
                com.shub39.grit.calendar.data.PhoneCalendars(
                    com.shub39.grit.calendar.data.PhoneCalendarSource()
                ),
                taskLists,
                taskRepo,
            )
        shoot(
            "calendar-account-local",
            ready = { viewModel.state.value.localCalendars.size == 1 },
        ) {
            val state by viewModel.state.collectAsState()
            com.shub39.grit.calendar.ui.account.AccountScreen(
                state,
                viewModel,
                onBack = {},
                onOpenSync = {},
            )
        }
        db.close()
    }
}
