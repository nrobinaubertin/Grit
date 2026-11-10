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
package com.shub39.grit.calendar.data

import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.KeyValueEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/** A calendar that another app keeps in the calendar storage of the phone. */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    /** The account of the app that owns the calendar, for example an email address. */
    val accountName: String?,
    /** ARGB color. */
    val color: Int,
    /** Whether the owning app shows the calendar. Grit follows it until the user chooses. */
    val visibleInProvider: Boolean,
)

/** A calendar of the phone, and whether Grit shows it. */
data class PhoneCalendarItem(val calendar: PhoneCalendar, val visible: Boolean)

/** One occurrence of an event from the calendar storage of the phone. */
data class PhoneInstance(
    val instanceId: Long,
    val eventId: Long,
    val calendarId: Long,
    /** UTC milliseconds. All-day occurrences start and end at midnight UTC. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String?,
    val location: String?,
    /** ARGB color of the event, or of its calendar. */
    val color: Int,
    val calendarName: String?,
    val recurring: Boolean,
)

data class PhoneEventDetails(val description: String?, val rrule: String?)

/**
 * Reads the calendar storage of the phone (the Android calendar provider), where apps such as
 * Google Calendar or DAVx5 keep their calendars. Grit never writes to it. The desktop app has none.
 */
@Single
expect class PhoneCalendarSource {
    /** False on platforms without a calendar storage. */
    val supported: Boolean

    fun hasPermission(): Boolean

    /** Emits at once, then after changes in the calendar storage. Slow collectors get one. */
    fun changes(): Flow<Unit>

    suspend fun calendars(): List<PhoneCalendar>

    /** Occurrences that overlap [from, to), in UTC milliseconds. */
    suspend fun instances(from: Long, to: Long): List<PhoneInstance>

    suspend fun details(eventId: Long): PhoneEventDetails?
}

/** What the user chose in Grit: whether to show the phone calendars, and which ones. */
data class PhoneSettings(val enabled: Boolean = false, val shown: Map<Long, Boolean> = emptyMap()) {
    fun isVisible(calendar: PhoneCalendar): Boolean =
        shown[calendar.id] ?: calendar.visibleInProvider

    companion object {
        const val PREFIX = "phone."
        const val ENABLED = "phone.enabled"
        const val CALENDAR = "phone.calendar."

        fun from(entries: List<KeyValueEntity>): PhoneSettings =
            PhoneSettings(
                enabled = entries.any { it.key == ENABLED && it.value == 1L },
                shown =
                    entries
                        .filter { it.key.startsWith(CALENDAR) }
                        .mapNotNull { entry ->
                            entry.key.removePrefix(CALENDAR).toLongOrNull()?.let {
                                it to (entry.value == 1L)
                            }
                        }
                        .toMap(),
            )
    }
}

/**
 * The calendars of other apps, read-only, with the choices of the user. Everything is empty until
 * the user turns them on and allows Grit to read the calendars.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Single
class PhoneCalendars(private val source: PhoneCalendarSource, db: CalendarDatabase) {
    private val dao = db.calendarDao()

    private val settings: Flow<PhoneSettings> =
        dao.valuesFlow(PhoneSettings.PREFIX).map(PhoneSettings::from).distinctUntilChanged()

    val supported: Boolean
        get() = source.supported

    /** True if the user turned the phone calendars on and Grit may read them. */
    val active: Flow<Boolean> =
        settings.map { it.enabled && source.hasPermission() }.distinctUntilChanged()

    /** All calendars of the phone, with their visibility in Grit. */
    fun calendars(): Flow<List<PhoneCalendarItem>> =
        settings.flatMapLatest { settings ->
            if (!settings.enabled || !source.hasPermission()) {
                flowOf(emptyList())
            } else {
                source.changes().map {
                    source.calendars().map { PhoneCalendarItem(it, settings.isVisible(it)) }
                }
            }
        }

    /** Occurrences of the shown phone calendars that overlap [from, to). */
    fun instances(from: Long, to: Long): Flow<List<PhoneInstance>> =
        settings.flatMapLatest { settings ->
            if (!settings.enabled || !source.hasPermission()) {
                flowOf(emptyList())
            } else {
                source.changes().map {
                    val shown =
                        source.calendars().filter(settings::isVisible).mapTo(HashSet()) { it.id }
                    if (shown.isEmpty()) emptyList()
                    else source.instances(from, to).filter { it.calendarId in shown }
                }
            }
        }

    suspend fun details(eventId: Long): PhoneEventDetails? = source.details(eventId)

    suspend fun setEnabled(enabled: Boolean) {
        dao.putValue(KeyValueEntity(PhoneSettings.ENABLED, if (enabled) 1 else 0))
    }

    suspend fun setVisible(calendarId: Long, visible: Boolean) {
        dao.putValue(KeyValueEntity(PhoneSettings.CALENDAR + calendarId, if (visible) 1 else 0))
    }
}
