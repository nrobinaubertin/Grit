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
package com.shub39.grit.calendar.ui.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.IcalTime
import com.shub39.grit.caldav.ical.toInstant
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.ui.home.simpleFrequency
import com.shub39.grit.calendar.ui.home.today
import com.shub39.grit.core.interfaces.SettingsDatastore
import java.time.ZoneId
import kotlin.time.toJavaInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

enum class RepeatChoice(val rule: String?) {
    None(null),
    Daily("FREQ=DAILY"),
    Weekly("FREQ=WEEKLY"),
    Monthly("FREQ=MONTHLY"),
    Yearly("FREQ=YEARLY"),
    /** A rule the editor cannot show; it is kept as it is. */
    Custom(null),
}

enum class EditorError {
    TitleRequired,
    EndBeforeStart,
    NoCalendar,
    SaveFailed,
}

@Immutable data class CalendarOption(val id: Long, val name: String, val color: Int?)

@Immutable
data class EditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = today(),
    val startTime: LocalTime = LocalTime(9, 0),
    val endDate: LocalDate = today(),
    val endTime: LocalTime = LocalTime(10, 0),
    /** Time zone of the times shown. It differs from the device zone for some existing events. */
    val zoneId: String = ZoneId.systemDefault().id,
    val repeat: RepeatChoice = RepeatChoice.None,
    val customRule: String? = null,
    val reminders: Set<Int> = emptySet(),
    val calendars: List<CalendarOption> = emptyList(),
    val calendarId: Long? = null,
    val hasExceptions: Boolean = false,
    val is24Hr: Boolean = true,
    val error: EditorError? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

@KoinViewModel
class EventEditorViewModel(
    private val store: CalendarStore,
    @Provided private val settings: SettingsDatastore,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var eventId: Long? = null
    private var loaded = false
    /** The times as loaded, so that unchanged times keep their exact iCalendar form. */
    private var original: Pair<IcalTime, IcalTime>? = null
    private var originalFields: EditorState? = null

    fun load(eventId: Long?, date: LocalDate?) {
        if (loaded) return
        loaded = true
        this.eventId = eventId
        if (eventId == null) {
            // A new event needs no database read to show its form: show it at once, and fill
            // in the calendar list when it arrives
            val day = date ?: today()
            val now = java.time.LocalTime.now()
            val startTime =
                if (day == today() && now.hour < 23) LocalTime(now.hour + 1, 0) else LocalTime(9, 0)
            val start = LocalDateTime(day, startTime)
            val end = start.plusHours(1)
            _state.value =
                EditorState(
                    loading = false,
                    isNew = true,
                    startDate = start.date,
                    startTime = start.time,
                    endDate = end.date,
                    endTime = end.time,
                    reminders = setOf(10),
                )
            originalFields = _state.value
        }
        viewModelScope.launch {
            val is24Hr = settings.getIs24Hr().first()
            if (eventId == null) {
                val writable =
                    store
                        .collectionsFlow()
                        .first()
                        .filter { it.canWrite && it.supportsEvents && it.visible }
                        .map { CalendarOption(it.id, it.displayName ?: it.url, it.color) }
                _state.update {
                    it.copy(
                        calendars = writable,
                        calendarId = it.calendarId ?: writable.firstOrNull()?.id,
                        is24Hr = is24Hr,
                        error = if (writable.isEmpty()) EditorError.NoCalendar else it.error,
                    )
                }
                return@launch
            }
            val details = store.details(eventId) ?: return@launch
            val info = details.info
            original = info.start to info.end
            val deviceZone = ZoneId.systemDefault().id
            val loadedState =
                when (val start = info.start) {
                    is IcalTime.Date -> {
                        val endDate =
                            (info.end as? IcalTime.Date)?.date?.minus(DatePeriod(days = 1))
                        EditorState(
                            allDay = true,
                            startDate = start.date,
                            endDate = maxOf(start.date, endDate ?: start.date),
                        )
                    }
                    else -> {
                        val zone = (start as? IcalTime.Zoned)?.zoneId ?: deviceZone
                        val s = start.localIn(zone)
                        val e = info.end.localIn(zone)
                        EditorState(
                            startDate = s.date,
                            startTime = s.time,
                            endDate = e.date,
                            endTime = e.time,
                            zoneId = zone,
                        )
                    }
                }
            val frequency = info.rrule?.let(::simpleFrequency)
            val repeat =
                when {
                    info.rrule == null -> RepeatChoice.None
                    frequency == null -> RepeatChoice.Custom
                    else ->
                        RepeatChoice.entries.firstOrNull { it.rule == "FREQ=$frequency" }
                            ?: RepeatChoice.Custom
                }
            _state.value =
                loadedState.copy(
                    loading = false,
                    isNew = false,
                    title = info.summary.orEmpty(),
                    location = info.location.orEmpty(),
                    description = info.description.orEmpty(),
                    repeat = repeat,
                    customRule = info.rrule.takeIf { repeat == RepeatChoice.Custom },
                    reminders = info.reminderMinutes.toSet(),
                    calendars =
                        listOf(
                            CalendarOption(
                                details.collection.id,
                                details.collection.displayName ?: details.collection.url,
                                details.collection.color,
                            )
                        ),
                    calendarId = details.collection.id,
                    hasExceptions = info.hasExceptions,
                    is24Hr = is24Hr,
                )
            originalFields = _state.value
        }
    }

    fun update(change: (EditorState) -> EditorState) =
        _state.update { change(it).copy(error = null) }

    fun setStartDate(date: LocalDate) = update { state ->
        // Moving the start moves the end by the same amount
        val shift = date.toEpochDays() - state.startDate.toEpochDays()
        state.copy(startDate = date, endDate = state.endDate.plus(DatePeriod(days = shift.toInt())))
    }

    fun setStartTime(time: LocalTime) = update { state ->
        val start = LocalDateTime(state.startDate, state.startTime)
        val end = LocalDateTime(state.endDate, state.endTime)
        val zone = TimeZone.UTC
        val length = end.toInstant(zone) - start.toInstant(zone)
        val newEnd =
            (LocalDateTime(state.startDate, time).toInstant(zone) + length).toLocalDateTime(zone)
        state.copy(startTime = time, endDate = newEnd.date, endTime = newEnd.time)
    }

    fun save() {
        val state = _state.value
        if (state.title.isBlank())
            return _state.update { it.copy(error = EditorError.TitleRequired) }
        val calendarId =
            state.calendarId ?: return _state.update { it.copy(error = EditorError.NoCalendar) }
        val (start, end) = times(state)
        val startInstant = start.toInstant(ZoneId.systemDefault()).toJavaInstant()
        val endInstant = end.toInstant(ZoneId.systemDefault()).toJavaInstant()
        if (
            !endInstant.isAfter(startInstant) &&
                !(state.allDay && !endInstant.isBefore(startInstant))
        ) {
            return _state.update { it.copy(error = EditorError.EndBeforeStart) }
        }
        val draft =
            EventDraft(
                summary = state.title.trim(),
                start = start,
                end = end,
                location = state.location.trim().ifEmpty { null },
                description = state.description.trim().ifEmpty { null },
                rrule =
                    if (state.repeat == RepeatChoice.Custom) state.customRule
                    else state.repeat.rule,
                reminderMinutes = state.reminders.sorted(),
            )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = eventId
                if (id == null) store.createEvent(calendarId, draft)
                else store.updateEvent(id, draft)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, error = EditorError.SaveFailed) }
            }
        }
    }

    /** True if the change touches the start or the repeat rule of a series with exceptions. */
    fun changesSeries(state: EditorState): Boolean {
        val before = originalFields ?: return false
        return state.hasExceptions &&
            (state.startDate != before.startDate ||
                state.startTime != before.startTime ||
                state.allDay != before.allDay ||
                state.repeat != before.repeat)
    }

    private fun times(state: EditorState): Pair<IcalTime, IcalTime> {
        val before = originalFields
        val unchanged =
            before != null &&
                state.allDay == before.allDay &&
                state.startDate == before.startDate &&
                state.startTime == before.startTime &&
                state.endDate == before.endDate &&
                state.endTime == before.endTime
        original?.let { if (unchanged) return it }
        if (state.allDay) {
            return IcalTime.Date(state.startDate) to
                IcalTime.Date(maxOf(state.endDate, state.startDate).plus(DatePeriod(days = 1)))
        }
        val floating = original?.first is IcalTime.Floating
        fun time(date: LocalDate, time: LocalTime): IcalTime =
            if (floating) IcalTime.Floating(LocalDateTime(date, time))
            else IcalTime.Zoned(LocalDateTime(date, time), state.zoneId)
        return time(state.startDate, state.startTime) to time(state.endDate, state.endTime)
    }
}

private fun LocalDateTime.plusHours(hours: Int): LocalDateTime {
    val zone = TimeZone.UTC
    return (toInstant(zone) + kotlin.time.Duration.parse("${hours}h")).toLocalDateTime(zone)
}

/** The wall-clock time of [this] in [zoneId]. */
private fun IcalTime.localIn(zoneId: String): LocalDateTime =
    when (this) {
        is IcalTime.Zoned -> if (this.zoneId == zoneId) dateTime else convert(zoneId)
        is IcalTime.Floating -> dateTime
        is IcalTime.Date -> LocalDateTime(date, LocalTime(0, 0))
        is IcalTime.Utc -> convert(zoneId)
    }

private fun IcalTime.convert(zoneId: String): LocalDateTime =
    toInstant(ZoneId.systemDefault())
        .toJavaInstant()
        .atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()))
        .toLocalDateTime()
        .toKotlinLocalDateTime()
