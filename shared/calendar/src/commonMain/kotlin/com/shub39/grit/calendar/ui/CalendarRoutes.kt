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
package com.shub39.grit.calendar.ui

import com.shub39.grit.shared.ui.navigation.CalendarRoute
import kotlinx.datetime.LocalDate

/**
 * Creates an event ([eventId] null, on [date]) or edits event [eventId]. [session] makes every
 * opening a new route, because Grit keeps ViewModels for the whole activity.
 */
data class EventEditor(
    val eventId: Long? = null,
    val date: LocalDate? = null,
    /** Start hour for a new event, when created from the week view. */
    val hour: Int? = null,
    val session: Long = System.nanoTime(),
) : CalendarRoute

/** Account and calendar settings. */
data object CalendarAccount : CalendarRoute
