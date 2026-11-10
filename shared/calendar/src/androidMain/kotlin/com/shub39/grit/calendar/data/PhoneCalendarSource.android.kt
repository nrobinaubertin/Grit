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
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** Reads the Android calendar provider. Every query runs on the IO dispatcher. */
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
                Calendars.VISIBLE,
            ),
            selection = "${Calendars.SYNC_EVENTS} = 1",
            sort = "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        ) { cursor ->
            PhoneCalendar(
                id = cursor.getLong(0),
                name = cursor.getString(1) ?: cursor.getString(2) ?: "?",
                accountName = cursor.getString(2),
                color = opaque(cursor.getInt(3)),
                visibleInProvider = cursor.getInt(4) == 1,
            )
        }

    actual suspend fun instances(from: Long, to: Long): List<PhoneInstance> {
        val uri =
            Instances.CONTENT_URI.buildUpon()
                .also {
                    ContentUris.appendId(it, from)
                    ContentUris.appendId(it, to)
                }
                .build()
        return query(
            uri,
            arrayOf(
                Instances._ID,
                Instances.EVENT_ID,
                Instances.CALENDAR_ID,
                Instances.BEGIN,
                Instances.END,
                Instances.ALL_DAY,
                Instances.TITLE,
                Instances.EVENT_LOCATION,
                Instances.DISPLAY_COLOR,
                Instances.CALENDAR_DISPLAY_NAME,
                Instances.RRULE,
                Instances.RDATE,
                Instances.ORIGINAL_ID,
            ),
            // Cancelled occurrences and declined invitations are not on the user's schedule
            selection =
                "(${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})" +
                    " AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
                    "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED})",
            sort = "${Instances.BEGIN}, ${Instances.END}",
        ) { cursor ->
            PhoneInstance(
                instanceId = cursor.getLong(0),
                eventId = cursor.getLong(1),
                calendarId = cursor.getLong(2),
                begin = cursor.getLong(3),
                end = cursor.getLong(4),
                allDay = cursor.getInt(5) == 1,
                title = cursor.getString(6),
                location = cursor.getString(7),
                color = opaque(cursor.getInt(8)),
                calendarName = cursor.getString(9),
                recurring = !cursor.isNull(10) || !cursor.isNull(11) || !cursor.isNull(12),
            )
        }
    }

    actual suspend fun details(eventId: Long): PhoneEventDetails? =
        query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                arrayOf(Events.DESCRIPTION, Events.RRULE),
            ) { cursor ->
                PhoneEventDetails(description = cursor.getString(0), rrule = cursor.getString(1))
            }
            .firstOrNull()

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

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private companion object {
        const val TAG = "PhoneCalendars"
    }
}
