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

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Desktop: no notifications (the upstream desktop app has none either). */
@Single
actual class ReminderScheduler {
    actual suspend fun reschedule() = Unit
}

/** Desktop: sync every 30 minutes while the app runs, and 5 seconds after a local change. */
@Single(createdAtStart = true)
actual class SyncScheduler(
    private val manager: CalendarSyncManager,
    private val store: CalendarStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            store.indexCategories()
            while (isActive) {
                runCatching { manager.syncAll() }
                delay(30.minutes)
            }
        }
        @OptIn(FlowPreview::class)
        scope.launch { store.localChanges.debounce(5.seconds).collect { requestSync() } }
    }

    actual fun requestSync() {
        scope.launch { runCatching { manager.syncAll() } }
    }
}
