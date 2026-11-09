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

import org.koin.core.annotation.Single

/**
 * Schedules the next calendar reminder. On Android it uses one AlarmManager alarm for the earliest
 * pending reminder. The desktop app has no notifications.
 */
@Single
expect class ReminderScheduler {
    suspend fun reschedule()
}

/**
 * Runs sync in the background: periodically, and a few seconds after a local change. Created at
 * Koin start.
 */
@Single(createdAtStart = true)
expect class SyncScheduler {
    /** Requests a sync soon, for example when the calendar tab opens. */
    fun requestSync()
}
