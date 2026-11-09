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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import com.shub39.grit.caldav.Ical
import com.shub39.grit.shared.ui.navigation.CalendarHome
import com.shub39.grit.shared.ui.navigation.NavEntryContributor
import com.shub39.grit.shared.ui.navigation.Routes
import com.shub39.grit.shared.ui.navigation.TopLevelBackStack
import com.shub39.grit.shared.ui.navigation.fadeTransitionMetadata
import org.koin.core.annotation.Single

/** Adds the calendar screens to the app navigation. */
@Single(binds = [NavEntryContributor::class])
class CalendarNavEntries : NavEntryContributor {
    override fun EntryProviderScope<Routes>.entries(backStack: TopLevelBackStack) {
        entry<CalendarHome>(metadata = fadeTransitionMetadata()) {
            Box(
                modifier = Modifier.fillMaxSize().background(colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Calendar (ical4j ${Ical.libraryCheck()})")
            }
        }
    }
}
