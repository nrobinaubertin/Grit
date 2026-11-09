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
import com.shub39.grit.core.interfaces.SettingsDatastore
import com.shub39.grit.core.settings.Sections
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
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

    private fun event(
        id: Long,
        title: String,
        day: Int,
        hour: Int,
        color: Int,
        canWrite: Boolean = true,
    ) =
        DayEvent(
            instanceId = id,
            eventId = id,
            title = title,
            location = if (id % 2 == 0L) "Home" else null,
            begin = at(day, hour),
            end = at(day, hour + 1),
            allDay = false,
            originalStart = null,
            recurring = id == 3L,
            color = color,
            calendarName = if (canWrite) "family" else "alice-personal",
            canWrite = canWrite,
        )

    private val pink = 0xFFE91E63.toInt()
    private val indigo = 0xFF3F51B5.toInt()
    private val teal = 0xFF009688.toInt()

    private val sample =
        CalendarUiState(
            month = YearMonth(2026, 11),
            selectedDate = today,
            today = today,
            eventsByDay =
                mapOf(
                    today to
                        listOf(
                            event(1, "Dentist", 9, 9, indigo, canWrite = false),
                            event(2, "Groceries", 9, 18, pink),
                        ),
                    LocalDate(2026, 11, 12) to listOf(event(3, "Standup", 12, 9, teal)),
                    LocalDate(2026, 11, 20) to
                        listOf(event(4, "Dinner", 20, 19, pink), event(5, "Film", 20, 21, indigo)),
                ),
            agenda =
                listOf(
                    today to
                        listOf(
                            event(1, "Dentist", 9, 9, indigo),
                            event(2, "Groceries", 9, 18, pink),
                        ),
                    LocalDate(2026, 11, 12) to listOf(event(3, "Standup", 12, 9, teal)),
                ),
            canCreate = true,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )

    @Test fun month() = shoot("calendar-month") { CalendarHomeScreen(sample, {}, {}, {}, {}) }

    @Test
    fun agenda() =
        shoot("calendar-agenda") {
            CalendarHomeScreen(sample.copy(mode = CalendarMode.Agenda), {}, {}, {}, {})
        }

    @Test
    fun noCalendars() =
        shoot("calendar-empty") {
            CalendarHomeScreen(sample.copy(hasCalendars = false, canCreate = false), {}, {}, {}, {})
        }

    @Test
    fun detailSheet() =
        shoot("calendar-detail", root = 1) {
            CalendarHomeScreen(
                sample.copy(
                    details =
                        EventDetailsUi(
                            event = event(3, "Standup", 12, 9, teal),
                            description = "Daily sync with the team",
                            rrule = "FREQ=WEEKLY",
                        )
                ),
                {},
                {},
                {},
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
                        color = pink,
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
                    color = teal,
                )
            )
            dao.insertCollection(
                CollectionEntity(
                    accountId = account,
                    url = "https://dav/bob/family/",
                    displayName = "family",
                    color = pink,
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
        }
        val store = CalendarStore(db)
        runBlocking { store.createLocalCalendar("Personal", indigo) }
        val manager =
            com.shub39.grit.calendar.data.CalendarSyncManager(
                db,
                store,
                com.shub39.grit.calendar.data.CredentialStore(),
                com.shub39.grit.calendar.data.ReminderScheduler(),
            )
        val viewModel = com.shub39.grit.calendar.ui.account.AccountViewModel(store, manager)
        shoot(
            "calendar-account",
            ready = {
                viewModel.state.value.collections.size == 3 &&
                    viewModel.state.value.localCalendars.size == 1
            },
        ) {
            val state by viewModel.state.collectAsState()
            com.shub39.grit.calendar.ui.account.AccountScreen(state, viewModel, onBack = {})
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
        runBlocking { store.createLocalCalendar("Personal", indigo) }
        val manager =
            com.shub39.grit.calendar.data.CalendarSyncManager(
                db,
                store,
                com.shub39.grit.calendar.data.CredentialStore(),
                com.shub39.grit.calendar.data.ReminderScheduler(),
            )
        val viewModel = com.shub39.grit.calendar.ui.account.AccountViewModel(store, manager)
        shoot(
            "calendar-account-local",
            ready = { viewModel.state.value.localCalendars.size == 1 },
        ) {
            val state by viewModel.state.collectAsState()
            com.shub39.grit.calendar.ui.account.AccountScreen(state, viewModel, onBack = {})
        }
        db.close()
    }

    private object FakeSettings : SettingsDatastore {
        override fun getStartOfTheWeekPref() = flowOf(DayOfWeek.MONDAY)

        override suspend fun setStartOfWeek(day: DayOfWeek) = Unit

        override fun getStartingSectionPref() = flowOf(Sections.Tasks)

        override suspend fun setStartingPage(page: Sections) = Unit

        override fun getIs24Hr() = flowOf(true)

        override suspend fun setIs24Hr(pref: Boolean) = Unit

        override fun getNotificationsFlow() = flowOf(false)

        override suspend fun setNotifications(pref: Boolean) = Unit

        override fun getBiometricLockPref() = flowOf(false)

        override suspend fun setBiometricPref(pref: Boolean) = Unit

        override fun getTaskReorderPref() = flowOf(false)

        override suspend fun setTaskReorderPref(pref: Boolean) = Unit

        override fun getCompactViewPref() = flowOf(false)

        override suspend fun setCompactView(pref: Boolean) = Unit
    }
}
