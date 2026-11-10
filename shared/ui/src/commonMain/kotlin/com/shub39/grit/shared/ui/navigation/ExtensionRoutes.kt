/*
 * Copyright (C) 2026  Shubham Gorai
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
package com.shub39.grit.shared.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope

// Extension points for features that live in modules outside :shared:ui (calendar fork).

/** Tab marker of the calendar tab. */
data object CalendarTab : Routes

/** A route of the calendar tab. The nav bar maps every [CalendarRoute] to [CalendarTab]. */
interface CalendarRoute : Routes

/** Landing route of the calendar tab. */
data object CalendarHome : CalendarRoute

/**
 * Adds navigation entries from a module that :shared:ui cannot depend on. Implementations are bound
 * in Koin, and [GritNavDisplay] collects all of them.
 */
interface NavEntryContributor {
    fun EntryProviderScope<Routes>.entries(backStack: TopLevelBackStack)
}

/** Adds a section to the Backup page of the settings, for example scheduled backups. */
interface BackupPageContributor {
    @Composable fun Section()
}
