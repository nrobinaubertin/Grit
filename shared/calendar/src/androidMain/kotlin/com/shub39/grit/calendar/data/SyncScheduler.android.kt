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

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** WorkManager jobs: every 30 minutes with network, and 5 seconds after a local change. */
@Single(createdAtStart = true)
actual class SyncScheduler(context: Context, store: CalendarStore) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
        // REPLACE restarts the delay on every edit, so that a burst of edits gives one sync
        scope.launch { store.localChanges.collect { enqueue(ExistingWorkPolicy.REPLACE, 5) } }
    }

    actual fun requestSync() = enqueue(ExistingWorkPolicy.KEEP, 0)

    private fun enqueue(policy: ExistingWorkPolicy, delaySeconds: Long) {
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK,
            policy,
            OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build(),
        )
    }

    private companion object {
        const val PERIODIC_WORK = "calendar_sync_periodic"
        const val ONE_TIME_WORK = "calendar_sync_now"
    }
}

/** Runs one sync of all calendars. */
class CalendarSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        get<CalendarSyncManager>().syncAll()
        return Result.success()
    }
}
