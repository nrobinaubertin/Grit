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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.EntryProviderScope
import com.shub39.grit.calendar.ui.account.AccountScreen
import com.shub39.grit.calendar.ui.account.AccountViewModel
import com.shub39.grit.calendar.ui.editor.EventEditorScreen
import com.shub39.grit.calendar.ui.editor.EventEditorViewModel
import com.shub39.grit.calendar.ui.home.CalendarAction
import com.shub39.grit.calendar.ui.home.CalendarHomeScreen
import com.shub39.grit.calendar.ui.home.CalendarViewModel
import com.shub39.grit.shared.ui.navigation.CalendarHome
import com.shub39.grit.shared.ui.navigation.NavEntryContributor
import com.shub39.grit.shared.ui.navigation.Routes
import com.shub39.grit.shared.ui.navigation.TopLevelBackStack
import com.shub39.grit.shared.ui.navigation.fadeTransitionMetadata
import com.shub39.grit.shared.ui.navigation.horizontalTransitionMetadata
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.Single

/** Sub-screens open faster than the upstream default of 500 ms. */
private const val TRANSITION_MS = 300

/**
 * The event editor as a layer above the calendar. Closing it reveals the calendar as it was,
 * without building it again. Its ViewModel lives as long as the layer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EditorLayer(route: EventEditor?, onClose: () -> Unit) {
    val last = remember { mutableStateOf<EventEditor?>(null) }
    if (route != null) last.value = route
    BackHandler(enabled = route != null, onBack = onClose)
    AnimatedVisibility(
        visible = route != null,
        enter = slideInVertically(tween(TRANSITION_MS)) { it },
        exit = slideOutVertically(tween(TRANSITION_MS)) { it },
    ) {
        val current = last.value ?: return@AnimatedVisibility
        key(current) {
            val owner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                val viewModel = koinViewModel<EventEditorViewModel>()
                LaunchedEffect(current) {
                    viewModel.load(current.eventId, current.date, current.hour)
                }
                val state by viewModel.state.collectAsStateWithLifecycle()
                EventEditorScreen(
                    state = state,
                    viewModel = viewModel,
                    onClose = onClose,
                    modifier = Modifier.background(colorScheme.background),
                )
            }
        }
    }
}

/** Adds the calendar screens to the app navigation. */
@Single(binds = [NavEntryContributor::class])
class CalendarNavEntries : NavEntryContributor {
    override fun EntryProviderScope<Routes>.entries(backStack: TopLevelBackStack) {
        entry<CalendarHome>(metadata = fadeTransitionMetadata()) {
            val viewModel = koinViewModel<CalendarViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            // One instance for the life of the screen, so that the views can skip recomposition
            val onAction =
                remember(viewModel) { { action: CalendarAction -> viewModel.onAction(action) } }
            var editor by remember { mutableStateOf<EventEditor?>(null) }
            Box(modifier = Modifier.fillMaxSize()) {
                CalendarHomeScreen(
                    state = state,
                    onAction = onAction,
                    onOpenAccount = { backStack.backStack.add(CalendarAccount) },
                    onCreateEvent = { date, hour ->
                        editor = EventEditor(date = date, hour = hour)
                    },
                    onEditEvent = { id -> editor = EventEditor(eventId = id) },
                )
                EditorLayer(editor, onClose = { editor = null })
            }
        }

        entry<CalendarAccount>(metadata = horizontalTransitionMetadata(TRANSITION_MS)) {
            val viewModel = koinViewModel<AccountViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            AccountScreen(state = state, viewModel = viewModel, onBack = { backStack.removeLast() })
        }
    }
}
