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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shub39.grit.core.interfaces.SettingsDatastore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Receives the reminder alarm, and the system broadcasts after which alarms must be set again:
 * boot, app update, clock change and time zone change.
 */
class CalendarAlarmReceiver : BroadcastReceiver(), KoinComponent {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val reminders = get<ReminderScheduler>()
                when (intent.action) {
                    ReminderScheduler.ACTION_REMINDER -> {
                        // Grit's "pause notifications" setting applies to calendar reminders too
                        val paused = get<SettingsDatastore>().getNotificationsFlow().first()
                        reminders.fire(paused)
                    }
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        // Floating and all-day events depend on the device time zone
                        get<CalendarStore>().expandAll()
                        reminders.reschedule()
                    }
                    else -> reminders.reschedule()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Same handling as [CalendarAlarmReceiver], registered for system broadcasts (exported). */
class CalendarTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only forward the system actions that the manifest filter lets through
        if (intent.action == ReminderScheduler.ACTION_REMINDER) return
        CalendarAlarmReceiver().onReceive(context, intent)
    }
}
