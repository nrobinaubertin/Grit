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
package com.shub39.grit.calendar.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.SyncScheduler
import com.shub39.grit.calendar.data.db.InstanceRow
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.core.interfaces.SettingsDatastore
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import kotlinx.datetime.yearMonth
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

enum class CalendarMode {
    Month,
    Agenda,
}

/** One event on one day. A multi-day event appears on each of its days. */
@Immutable
data class DayEvent(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val location: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val originalStart: Long?,
    val recurring: Boolean,
    val color: Int?,
    val calendarName: String?,
    val canWrite: Boolean,
)

/** What the event detail sheet shows. */
@Immutable
data class EventDetailsUi(val event: DayEvent, val description: String?, val rrule: String?)

@Immutable
data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.Month,
    val month: YearMonth = today().yearMonth,
    val selectedDate: LocalDate = today(),
    val today: LocalDate = today(),
    val eventsByDay: Map<LocalDate, List<DayEvent>> = emptyMap(),
    val agenda: List<Pair<LocalDate, List<DayEvent>>> = emptyList(),
    /** False until the user creates a calendar on this device or connects a server. */
    val hasCalendars: Boolean = true,
    val hasServer: Boolean = false,
    val canCreate: Boolean = false,
    val syncing: Boolean = false,
    val syncError: String? = null,
    val pendingChanges: Int = 0,
    val is24Hr: Boolean = true,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val details: EventDetailsUi? = null,
)

sealed interface CalendarAction {
    data object Opened : CalendarAction

    data class SelectDate(val date: LocalDate) : CalendarAction

    data class ShowMonth(val month: YearMonth) : CalendarAction

    data class SetMode(val mode: CalendarMode) : CalendarAction

    data object Today : CalendarAction

    data object Refresh : CalendarAction

    data object LoadMoreAgenda : CalendarAction

    data class OpenEvent(val event: DayEvent) : CalendarAction

    data object CloseEvent : CalendarAction

    data class DeleteEvent(val eventId: Long) : CalendarAction

    data class DeleteOccurrence(val eventId: Long, val originalStart: Long) : CalendarAction

    /** Creates a first calendar on this device, from the empty screen. */
    data class CreateLocalCalendar(val name: String) : CalendarAction
}

private data class LocalState(
    val mode: CalendarMode = CalendarMode.Month,
    val month: YearMonth = today().yearMonth,
    val selectedDate: LocalDate = today(),
    val agendaDays: Int = AGENDA_PAGE_DAYS,
    val details: EventDetailsUi? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class CalendarViewModel(
    private val store: CalendarStore,
    private val syncManager: CalendarSyncManager,
    private val syncScheduler: SyncScheduler,
    @Provided settings: SettingsDatastore,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    private val monthEvents: Flow<Map<LocalDate, List<DayEvent>>> =
        local
            .map { it.month }
            .distinctUntilChanged()
            .flatMapLatest { month ->
                // The grid shows parts of the previous and next months
                val first = LocalDate(month.year, month.month, 1).minus(DatePeriod(days = 7))
                val last =
                    LocalDate(month.year, month.month, 1).plus(DatePeriod(months = 1, days = 7))
                eventsBetween(first, last)
            }

    private val agendaEvents: Flow<List<Pair<LocalDate, List<DayEvent>>>> =
        local
            .map { it.agendaDays }
            .distinctUntilChanged()
            .flatMapLatest { days ->
                val first = today()
                eventsBetween(first, first.plus(DatePeriod(days = days)))
                    .map { byDay ->
                        byDay.filterKeys { it >= first }.toList().sortedBy { it.first }
                    }
                    .flowOn(Dispatchers.Default)
            }

    private val settingsFlow =
        combine(settings.getIs24Hr(), settings.getStartOfTheWeekPref()) { is24Hr, firstDay ->
            is24Hr to firstDay
        }

    private val syncFlow =
        combine(
            store.accountsFlow(),
            store.collectionsFlow(),
            syncManager.running,
            store.pendingCountFlow(),
        ) { accounts, collections, running, pending ->
            SyncInfo(
                hasCalendars = collections.isNotEmpty(),
                hasServer = accounts.any { !it.local },
                canCreate = collections.any { it.canWrite && it.supportsEvents },
                syncing = running,
                error =
                    accounts.firstNotNullOfOrNull { it.lastError }
                        ?: collections.firstNotNullOfOrNull { c ->
                            c.lastError?.let { "${c.displayName ?: c.url}: $it" }
                        },
                pending = pending,
            )
        }

    val state: StateFlow<CalendarUiState> =
        combine(local, monthEvents, agendaEvents, settingsFlow, syncFlow) {
                local,
                month,
                agenda,
                settings,
                sync ->
                CalendarUiState(
                    mode = local.mode,
                    month = local.month,
                    selectedDate = local.selectedDate,
                    today = today(),
                    eventsByDay = month,
                    agenda = agenda,
                    hasCalendars = sync.hasCalendars,
                    hasServer = sync.hasServer,
                    canCreate = sync.canCreate,
                    syncing = sync.syncing,
                    syncError = sync.error,
                    pendingChanges = sync.pending,
                    is24Hr = settings.first,
                    firstDayOfWeek = settings.second,
                    details = local.details,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUiState())

    fun onAction(action: CalendarAction) {
        when (action) {
            CalendarAction.Opened -> syncScheduler.requestSync()
            is CalendarAction.SelectDate ->
                local.update { it.copy(selectedDate = action.date, month = action.date.yearMonth) }
            is CalendarAction.ShowMonth -> local.update { it.copy(month = action.month) }
            is CalendarAction.SetMode -> local.update { it.copy(mode = action.mode) }
            CalendarAction.Today ->
                local.update { it.copy(selectedDate = today(), month = today().yearMonth) }
            CalendarAction.Refresh -> viewModelScope.launch { syncManager.syncAll() }
            CalendarAction.LoadMoreAgenda ->
                local.update { it.copy(agendaDays = it.agendaDays + AGENDA_PAGE_DAYS) }
            is CalendarAction.OpenEvent ->
                viewModelScope.launch {
                    val details = store.details(action.event.eventId) ?: return@launch
                    local.update {
                        it.copy(
                            details =
                                EventDetailsUi(
                                    event = action.event,
                                    description = details.info.description,
                                    rrule = details.info.rrule,
                                )
                        )
                    }
                }
            CalendarAction.CloseEvent -> local.update { it.copy(details = null) }
            is CalendarAction.DeleteEvent ->
                viewModelScope.launch {
                    local.update { it.copy(details = null) }
                    store.deleteEvent(action.eventId)
                }
            is CalendarAction.CreateLocalCalendar ->
                viewModelScope.launch {
                    store.createLocalCalendar(action.name, LOCAL_COLORS.first())
                }
            is CalendarAction.DeleteOccurrence ->
                viewModelScope.launch {
                    local.update { it.copy(details = null) }
                    store.deleteOccurrence(action.eventId, action.originalStart)
                }
        }
    }

    /** Events per local day in [first, last), with repeating events expanded if needed. */
    private fun eventsBetween(
        first: LocalDate,
        last: LocalDate,
    ): Flow<Map<LocalDate, List<DayEvent>>> {
        val zone = ZoneId.systemDefault()
        val timeZone = zone.toKotlinTimeZone()
        val from = first.atStartOfDayIn(timeZone).toEpochMilliseconds()
        val to = last.atStartOfDayIn(timeZone).toEpochMilliseconds()
        return flow {
                store.ensureWindow(from, to)
                // All-day instances are stored at midnight UTC, so widen the query by one day
                emitAll(store.instances(from - DAY_MS, to + DAY_MS))
            }
            .map { rows -> rows.byDay(zone, first, last) }
            // Grouping by day converts every instance: keep it off the main thread
            .flowOn(Dispatchers.Default)
    }

    private class SyncInfo(
        val hasCalendars: Boolean,
        val hasServer: Boolean,
        val canCreate: Boolean,
        val syncing: Boolean,
        val error: String?,
        val pending: Int,
    )
}

private const val AGENDA_PAGE_DAYS = 60

/** Colors offered for calendars on this device (ARGB). */
val LOCAL_COLORS =
    listOf(
        0xFF3F51B5.toInt(),
        0xFFE91E63.toInt(),
        0xFF009688.toInt(),
        0xFFFF9800.toInt(),
        0xFF4CAF50.toInt(),
        0xFF9C27B0.toInt(),
        0xFF2196F3.toInt(),
        0xFF795548.toInt(),
    )
private const val DAY_MS = 24 * 60 * 60 * 1000L

internal fun today(): LocalDate = java.time.LocalDate.now().toKotlinLocalDate()

/** Spreads instances over the local days they cover, within [first, last). */
internal fun List<InstanceRow>.byDay(
    zone: ZoneId,
    first: LocalDate,
    last: LocalDate,
): Map<LocalDate, List<DayEvent>> {
    val result = sortedMapOf<LocalDate, MutableList<DayEvent>>()
    for (row in this) {
        val startDay: java.time.LocalDate
        val endDay: java.time.LocalDate
        if (row.allDay) {
            startDay = Formatting.allDayDate(row.begin)
            endDay = maxOf(startDay, Formatting.allDayDate(row.end).minusDays(1))
        } else {
            startDay = java.time.Instant.ofEpochMilli(row.begin).atZone(zone).toLocalDate()
            // An event that ends at midnight does not appear on the next day
            val lastMoment = if (row.end > row.begin) row.end - 1 else row.end
            endDay = java.time.Instant.ofEpochMilli(lastMoment).atZone(zone).toLocalDate()
        }
        val event =
            DayEvent(
                instanceId = row.id,
                eventId = row.eventId,
                title = row.title ?: "",
                location = row.location,
                begin = row.begin,
                end = row.end,
                allDay = row.allDay,
                originalStart = row.originalStart,
                recurring = row.recurring,
                color = row.color,
                calendarName = row.calendarName,
                canWrite = row.canWrite,
            )
        var day = maxOf(startDay, first.toJavaLocalDate())
        val stop = minOf(endDay, last.toJavaLocalDate().minusDays(1))
        while (!day.isAfter(stop)) {
            result.getOrPut(day.toKotlinLocalDate()) { mutableListOf() } += event
            day = day.plusDays(1)
        }
    }
    return result.mapValues { (_, events) ->
        events.sortedWith(compareByDescending<DayEvent> { it.allDay }.thenBy { it.begin })
    }
}
