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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** Reads the Android calendar provider, never writes it. Every query runs on the IO dispatcher. */
@Single
actual class PhoneCalendarSource(private val context: Context) {
    private val resolver
        get() = context.contentResolver

    actual val supported: Boolean = true

    actual fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    actual fun changes(): Flow<Unit> =
        callbackFlow {
                trySend(Unit)
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }
                val registered =
                    try {
                        resolver.registerContentObserver(
                            CalendarContract.CONTENT_URI,
                            true,
                            observer,
                        )
                        true
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Cannot watch the calendar provider", e)
                        false
                    }
                awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
            }
            // A sync in another app can change many rows: read again once, not once per row
            .conflate()

    actual suspend fun calendars(): List<PhoneCalendar> =
        query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_COLOR,
            ),
            selection = "${Calendars.SYNC_EVENTS} = 1",
            sort = "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        ) { cursor ->
            PhoneCalendar(
                id = cursor.getLong(0),
                name = cursor.getString(1) ?: cursor.getString(2) ?: "?",
                accountName = cursor.getString(2),
                color = cursor.getInt(3) or 0xFF000000.toInt(),
            )
        }

    actual suspend fun events(calendarId: Long): List<PhoneEventRow> {
        val rows =
            query(
                Events.CONTENT_URI,
                arrayOf(
                    Events._ID,
                    Events.UID_2445,
                    Events.TITLE,
                    Events.DESCRIPTION,
                    Events.EVENT_LOCATION,
                    Events.DTSTART,
                    Events.DTEND,
                    Events.DURATION,
                    Events.ALL_DAY,
                    Events.EVENT_TIMEZONE,
                    Events.RRULE,
                    Events.RDATE,
                    Events.EXRULE,
                    Events.EXDATE,
                    Events.ORIGINAL_ID,
                    Events.ORIGINAL_INSTANCE_TIME,
                    Events.ORIGINAL_ALL_DAY,
                    Events.STATUS,
                ),
                selection = "${Events.CALENDAR_ID} = $calendarId AND ${Events.DELETED} = 0",
                sort = "${Events._ID}",
            ) { c ->
                val id = c.getLong(0)
                PhoneEventRow(
                    id = id,
                    uid = c.getString(1),
                    title = c.getString(2),
                    description = c.getString(3),
                    location = c.getString(4),
                    start = c.getLong(5),
                    end = if (c.isNull(6)) null else c.getLong(6),
                    duration = c.getString(7),
                    allDay = c.getInt(8) == 1,
                    timeZone = c.getString(9),
                    rrule = c.getString(10),
                    rdate = c.getString(11),
                    exrule = c.getString(12),
                    exdate = c.getString(13),
                    originalId = if (c.isNull(14)) null else c.getLong(14),
                    originalInstanceTime = if (c.isNull(15)) null else c.getLong(15),
                    originalAllDay = c.getInt(16) == 1,
                    status = if (c.isNull(17)) null else c.getInt(17),
                )
            }
        // Reminders by event id, in chunks that keep the query short
        val reminders = mutableMapOf<Long, MutableList<Int>>()
        for (chunk in rows.map { it.id }.chunked(500)) {
            query(
                Reminders.CONTENT_URI,
                arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
                selection =
                    "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")}) AND " +
                        "${Reminders.METHOD} IN (${Reminders.METHOD_DEFAULT}, ${Reminders.METHOD_ALERT})",
            ) { cursor ->
                reminders.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getInt(1)
            }
        }
        return rows.map { it.copy(reminderMinutes = reminders[it.id].orEmpty()) }
    }

    /** Runs a query, or returns an empty list without the permission. */
    private suspend fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        sort: String? = null,
        row: (Cursor) -> T,
    ): List<T> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            try {
                resolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                    buildList(cursor.count) { while (cursor.moveToNext()) add(row(cursor)) }
                } ?: emptyList()
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read the calendar provider", e)
                emptyList()
            }
        }

    private companion object {
        const val TAG = "PhoneCalendars"
    }
}
