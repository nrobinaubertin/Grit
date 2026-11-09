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
import com.shub39.grit.calendar.data.CategoryColors
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
    Week,
    Agenda,
}

/** One occurrence of an event, with the local days it covers. */
@Immutable
data class DayEvent(
    val instanceId: Long,
    val eventId: Long,
    val title: String,
    val location: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** First and last local day that the occurrence covers. */
    val startDay: LocalDate,
    val endDay: LocalDate,
    val originalStart: Long?,
    val recurring: Boolean,
    val category: String?,
    /** ARGB color of the category, or null without a category. */
    val color: Int?,
    val calendarName: String?,
    val canWrite: Boolean,
)

/** What the event detail sheet shows. */
@Immutable
data class EventDetailsUi(val event: DayEvent, val description: String?, val rrule: String?)

/** A calendar in the filter sheet. */
@Immutable
data class CalendarFilterItem(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val local: Boolean,
)

/** Events of the months around the shown month: per day, and in lanes per week row. */
@Immutable
data class MonthData(
    val byDay: Map<LocalDate, List<DayEvent>> = emptyMap(),
    val weeks: Map<LocalDate, WeekLanes> = emptyMap(),
)

@Immutable
data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.Month,
    val month: YearMonth = today().yearMonth,
    val week: LocalDate = today(),
    val selectedDate: LocalDate = today(),
    val today: LocalDate = today(),
    val monthData: MonthData = MonthData(),
    val weeks: Map<LocalDate, WeekData> = emptyMap(),
    val agenda: List<Pair<LocalDate, List<DayEvent>>> = emptyList(),
    /** False until the user creates a calendar on this device or connects a server. */
    val hasCalendars: Boolean = true,
    val hasServer: Boolean = false,
    val calendars: List<CalendarFilterItem> = emptyList(),
    val canCreate: Boolean = false,
    val syncing: Boolean = false,
    val syncError: String? = null,
    val pendingChanges: Int = 0,
    val is24Hr: Boolean = true,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val details: EventDetailsUi? = null,
) {
    val eventsByDay: Map<LocalDate, List<DayEvent>>
        get() = monthData.byDay
}

sealed interface CalendarAction {
    data object Opened : CalendarAction

    data class SelectDate(val date: LocalDate) : CalendarAction

    data class ShowMonth(val month: YearMonth) : CalendarAction

    /** [start] is the first day of the week. */
    data class ShowWeek(val start: LocalDate) : CalendarAction

    data class SetMode(val mode: CalendarMode) : CalendarAction

    data object Today : CalendarAction

    data object Refresh : CalendarAction

    data object LoadMoreAgenda : CalendarAction

    data class OpenEvent(val event: DayEvent) : CalendarAction

    data object CloseEvent : CalendarAction

    data class DeleteEvent(val eventId: Long) : CalendarAction

    data class DeleteOccurrence(val eventId: Long, val originalStart: Long) : CalendarAction

    data class SetCalendarVisible(val id: Long, val visible: Boolean) : CalendarAction

    /** Creates a first calendar on this device, from the empty screen. */
    data class CreateLocalCalendar(val name: String) : CalendarAction
}

private data class LocalState(
    val mode: CalendarMode = CalendarMode.Month,
    val month: YearMonth = today().yearMonth,
    /** First day of the week shown in the week view, or null for the week of the selection. */
    val week: LocalDate? = null,
    val selectedDate: LocalDate = today(),
    val agendaDays: Int = AGENDA_PAGE_DAYS,
    val details: EventDetailsUi? = null,
)

private class Meta(
    val is24Hr: Boolean,
    val firstDayOfWeek: DayOfWeek,
    val hasCalendars: Boolean,
    val hasServer: Boolean,
    val calendars: List<CalendarFilterItem>,
    val canCreate: Boolean,
    val syncing: Boolean,
    val error: String?,
    val pending: Int,
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
    private val firstDayOfWeek = settings.getStartOfTheWeekPref().distinctUntilChanged()

    private val monthData: Flow<MonthData> =
        combine(local.map { it.month }.distinctUntilChanged(), firstDayOfWeek, ::Pair)
            .flatMapLatest { (month, firstDay) ->
                // The previous and next months too: the pager keeps them ready for a swipe
                val start = LocalDate(month.year, month.month, 1)
                val first = start.minus(DatePeriod(months = 1, days = 7))
                val last = start.plus(DatePeriod(months = 2, days = 7))
                eventsBetween(first, last).map { events ->
                    val weeks = mutableMapOf<LocalDate, WeekLanes>()
                    var week = startOfWeek(first, firstDay)
                    while (week < last) {
                        weeks[week] = layoutWeek(week, events, MONTH_LANES)
                        week = week.plus(DatePeriod(days = 7))
                    }
                    MonthData(byDay(events, first, last), weeks)
                }
            }
            // A sync that changes nothing must not redraw the grid
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private val weekData: Flow<Map<LocalDate, WeekData>> =
        combine(local, firstDayOfWeek) { local, firstDay ->
                local.week ?: startOfWeek(local.selectedDate, firstDay)
            }
            .distinctUntilChanged()
            .flatMapLatest { week ->
                // The previous and next weeks too, for the pager
                val first = week.minus(DatePeriod(days = 7))
                val last = week.plus(DatePeriod(days = 14))
                eventsBetween(first, last).map { events ->
                    (-1..1).associate { offset ->
                        val start = week.plus(DatePeriod(days = 7 * offset))
                        start to
                            WeekData(
                                band =
                                    layoutWeek(
                                        start,
                                        events.filter { it.isBandEvent() },
                                        WEEK_BAND_LANES,
                                    ),
                                boxes = layoutTimed(start, events, ZoneId.systemDefault()),
                            )
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private val agendaEvents: Flow<List<Pair<LocalDate, List<DayEvent>>>> =
        local
            .map { it.agendaDays }
            .distinctUntilChanged()
            .flatMapLatest { days ->
                val first = today()
                val last = first.plus(DatePeriod(days = days))
                eventsBetween(first, last).map { events ->
                    byDay(events, first, last).toList().sortedBy { it.first }
                }
            }
            .flowOn(Dispatchers.Default)

    private val meta: Flow<Meta> =
        combine(
            combine(settings.getIs24Hr(), firstDayOfWeek, ::Pair),
            store.accountsFlow(),
            store.collectionsFlow(),
            syncManager.running,
            store.pendingCountFlow(),
        ) { (is24Hr, firstDay), accounts, collections, running, pending ->
            val localAccount = accounts.firstOrNull { it.local }?.id
            val events = collections.filter { it.supportsEvents }
            Meta(
                is24Hr = is24Hr,
                firstDayOfWeek = firstDay,
                hasCalendars = collections.isNotEmpty(),
                hasServer = accounts.any { !it.local },
                calendars =
                    events.map {
                        CalendarFilterItem(
                            id = it.id,
                            name = it.displayName ?: it.url,
                            visible = it.visible,
                            local = it.accountId == localAccount,
                        )
                    },
                canCreate = events.any { it.canWrite && it.visible },
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
        combine(local, monthData, weekData, agendaEvents, meta) { local, month, weeks, agenda, meta
                ->
                CalendarUiState(
                    mode = local.mode,
                    month = local.month,
                    week = local.week ?: startOfWeek(local.selectedDate, meta.firstDayOfWeek),
                    selectedDate = local.selectedDate,
                    today = today(),
                    monthData = month,
                    weeks = weeks,
                    agenda = agenda,
                    hasCalendars = meta.hasCalendars,
                    hasServer = meta.hasServer,
                    calendars = meta.calendars,
                    canCreate = meta.canCreate,
                    syncing = meta.syncing,
                    syncError = meta.error,
                    pendingChanges = meta.pending,
                    is24Hr = meta.is24Hr,
                    firstDayOfWeek = meta.firstDayOfWeek,
                    details = local.details,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUiState())

    fun onAction(action: CalendarAction) {
        when (action) {
            CalendarAction.Opened -> syncScheduler.requestSync()
            is CalendarAction.SelectDate ->
                local.update {
                    it.copy(selectedDate = action.date, month = action.date.yearMonth, week = null)
                }
            is CalendarAction.ShowMonth -> local.update { it.copy(month = action.month) }
            is CalendarAction.ShowWeek -> local.update { it.copy(week = action.start) }
            is CalendarAction.SetMode -> local.update { it.copy(mode = action.mode) }
            CalendarAction.Today ->
                local.update {
                    it.copy(selectedDate = today(), month = today().yearMonth, week = null)
                }
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
            is CalendarAction.DeleteOccurrence ->
                viewModelScope.launch {
                    local.update { it.copy(details = null) }
                    store.deleteOccurrence(action.eventId, action.originalStart)
                }
            is CalendarAction.SetCalendarVisible ->
                viewModelScope.launch { syncManager.setVisible(action.id, action.visible) }
            is CalendarAction.CreateLocalCalendar ->
                viewModelScope.launch { store.createLocalCalendar(action.name) }
        }
    }

    /** Distinct occurrences in [first, last), with repeating events expanded if needed. */
    private fun eventsBetween(first: LocalDate, last: LocalDate): Flow<List<DayEvent>> {
        val zone = ZoneId.systemDefault()
        val timeZone = zone.toKotlinTimeZone()
        val from = first.atStartOfDayIn(timeZone).toEpochMilliseconds()
        val to = last.atStartOfDayIn(timeZone).toEpochMilliseconds()
        return flow {
                store.ensureWindow(from, to)
                // All-day instances are stored at midnight UTC, so widen the query by one day
                emitAll(store.instances(from - DAY_MS, to + DAY_MS))
            }
            .map { rows -> rows.map { it.toDayEvent(zone) } }
            // Converting every instance must not happen on the main thread
            .flowOn(Dispatchers.Default)
    }
}

private const val AGENDA_PAGE_DAYS = 60
private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Lanes per day in the month grid, and in the all-day band of the week view. */
const val MONTH_LANES = 4
const val WEEK_BAND_LANES = 3

internal fun today(): LocalDate = java.time.LocalDate.now().toKotlinLocalDate()

internal fun startOfWeek(date: LocalDate, firstDay: DayOfWeek): LocalDate {
    val offset = (date.dayOfWeek.ordinal - firstDay.ordinal + 7) % 7
    return date.minus(DatePeriod(days = offset))
}

internal fun InstanceRow.toDayEvent(zone: ZoneId): DayEvent {
    val startDay: java.time.LocalDate
    val endDay: java.time.LocalDate
    if (allDay) {
        startDay = Formatting.allDayDate(begin)
        endDay = maxOf(startDay, Formatting.allDayDate(end).minusDays(1))
    } else {
        startDay = java.time.Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        // An event that ends at midnight does not appear on the next day
        val lastMoment = if (end > begin) end - 1 else end
        endDay = java.time.Instant.ofEpochMilli(lastMoment).atZone(zone).toLocalDate()
    }
    return DayEvent(
        instanceId = id,
        eventId = eventId,
        title = title ?: "",
        location = location,
        begin = begin,
        end = end,
        allDay = allDay,
        startDay = startDay.toKotlinLocalDate(),
        endDay = endDay.toKotlinLocalDate(),
        originalStart = originalStart,
        recurring = recurring,
        category = category,
        color = CategoryColors.argb(categoryColor),
        calendarName = calendarName,
        canWrite = canWrite,
    )
}

/** Spreads events over the local days they cover, within [first, last). */
internal fun byDay(
    events: List<DayEvent>,
    first: LocalDate,
    last: LocalDate,
): Map<LocalDate, List<DayEvent>> {
    val result = sortedMapOf<LocalDate, MutableList<DayEvent>>()
    for (event in events) {
        var day = maxOf(event.startDay, first).toJavaLocalDate()
        val stop = minOf(event.endDay, last.minus(DatePeriod(days = 1))).toJavaLocalDate()
        while (!day.isAfter(stop)) {
            result.getOrPut(day.toKotlinLocalDate()) { mutableListOf() } += event
            day = day.plusDays(1)
        }
    }
    return result.mapValues { (_, events) ->
        events.sortedWith(compareByDescending<DayEvent> { it.allDay }.thenBy { it.begin })
    }
}
