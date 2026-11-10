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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Single

/** The desktop has no shared calendar storage. */
@Single
actual class PhoneCalendarSource {
    actual val supported: Boolean = false

    actual fun hasPermission(): Boolean = false

    actual fun changes(): Flow<Unit> = flowOf(Unit)

    actual suspend fun calendars(): List<PhoneCalendar> = emptyList()

    actual suspend fun instances(from: Long, to: Long): List<PhoneInstance> = emptyList()

    actual suspend fun details(eventId: Long): PhoneEventDetails? = null
}
