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
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.EntryProviderScope
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Colors of tasks. The calendar module keeps them outside the upstream task database, so that the
 * task schema stays as upstream has it.
 */
interface TaskColors {
    /** ARGB colors by task id. */
    val colors: StateFlow<Map<Long, Int>>

    /** Saves the color of an existing task. Null removes it. */
    fun setColor(taskId: Long, color: Int?)

    /** The color that the editor chose for a new task, which has no id before it is saved. */
    fun setPending(color: Int?)

    /** Gives the pending color to the task that was just saved as [taskId]. */
    fun assignPending(taskId: Long)

    /** The row of colors in the task editor. */
    @Composable fun Picker(selected: Int?, onSelect: (Int?) -> Unit)
}

/** Sync of task lists with a server, which the calendar module provides. */
interface TaskSync {
    /** Asks for a sync soon, for example when the task list opens. */
    fun requestSync()
}

/** The task colors for the task screens: empty without a [TaskColors] module. */
val LocalTaskColors = compositionLocalOf<Map<Long, Int>> { emptyMap() }
