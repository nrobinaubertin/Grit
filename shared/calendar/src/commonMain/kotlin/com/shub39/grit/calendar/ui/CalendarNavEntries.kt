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
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import com.shub39.grit.calendar.ui.account.AccountScreen
import com.shub39.grit.calendar.ui.account.AccountViewModel
import com.shub39.grit.calendar.ui.editor.EventEditorScreen
import com.shub39.grit.calendar.ui.editor.EventEditorViewModel
import com.shub39.grit.calendar.ui.home.CalendarHomeScreen
import com.shub39.grit.calendar.ui.home.CalendarViewModel
import com.shub39.grit.shared.ui.navigation.CalendarHome
import com.shub39.grit.shared.ui.navigation.NavEntryContributor
import com.shub39.grit.shared.ui.navigation.Routes
import com.shub39.grit.shared.ui.navigation.TopLevelBackStack
import com.shub39.grit.shared.ui.navigation.fadeTransitionMetadata
import com.shub39.grit.shared.ui.navigation.horizontalTransitionMetadata
import com.shub39.grit.shared.ui.navigation.verticalTransitionMetadata
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.Single

/** Adds the calendar screens to the app navigation. */
@Single(binds = [NavEntryContributor::class])
class CalendarNavEntries : NavEntryContributor {
    override fun EntryProviderScope<Routes>.entries(backStack: TopLevelBackStack) {
        entry<CalendarHome>(metadata = fadeTransitionMetadata()) {
            val viewModel = koinViewModel<CalendarViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            CalendarHomeScreen(
                state = state,
                onAction = viewModel::onAction,
                onOpenAccount = { backStack.backStack.add(CalendarAccount) },
                onCreateEvent = { date -> backStack.backStack.add(EventEditor(date = date)) },
                onEditEvent = { id -> backStack.backStack.add(EventEditor(eventId = id)) },
            )
        }

        entry<EventEditor>(metadata = verticalTransitionMetadata()) { route ->
            val viewModel = koinViewModel<EventEditorViewModel>(key = route.toString())
            LaunchedEffect(route) { viewModel.load(route.eventId, route.date) }
            val state by viewModel.state.collectAsStateWithLifecycle()
            EventEditorScreen(
                state = state,
                viewModel = viewModel,
                onClose = { backStack.removeLast() },
                modifier = Modifier.background(colorScheme.background),
            )
        }

        entry<CalendarAccount>(metadata = horizontalTransitionMetadata()) {
            val viewModel = koinViewModel<AccountViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            AccountScreen(state = state, viewModel = viewModel, onBack = { backStack.removeLast() })
        }
    }
}
