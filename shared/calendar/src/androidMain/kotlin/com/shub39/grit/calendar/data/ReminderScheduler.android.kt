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

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import com.shub39.grit.calendar.data.db.AlarmEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.KeyValueEntity
import org.koin.core.annotation.Single

/**
 * Keeps one AlarmManager alarm set for the earliest pending reminder, as Android's own calendar
 * provider does. When it fires, every reminder between the watermark and now is shown, and the next
 * alarm is set.
 */
@Single
actual class ReminderScheduler(private val context: Context, db: CalendarDatabase) {
    private val dao = db.calendarDao()

    actual suspend fun reschedule() {
        val now = System.currentTimeMillis()
        var watermark = dao.getValue(WATERMARK)
        if (watermark == null || watermark < now - MAX_LATE_MS) {
            // Do not show reminders that are more than an hour late (phone off, first start)
            watermark = now
            dao.putValue(KeyValueEntity(WATERMARK, watermark))
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent()
        val next = dao.nextAlarm(watermark)
        if (next == null) {
            alarmManager.cancel(intent)
            return
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAt, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAt, intent)
        }
    }

    /** Shows the due reminders (unless [paused]) and sets the next alarm. */
    suspend fun fire(paused: Boolean) {
        val now = System.currentTimeMillis()
        val watermark = dao.getValue(WATERMARK) ?: (now - MAX_LATE_MS)
        if (!paused) dao.alarmsBetween(watermark, now).forEach(::notify)
        dao.putValue(KeyValueEntity(WATERMARK, now))
        reschedule()
    }

    private fun alarmIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CalendarAlarmReceiver::class.java).setAction(ACTION_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notify(alarm: AlarmEntity) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calendar events", NotificationManager.IMPORTANCE_HIGH)
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content =
            launch?.let {
                PendingIntent.getActivity(
                    context,
                    alarm.id.toInt(),
                    it,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val time =
            if (alarm.instanceAllDay) {
                DateUtils.formatDateTime(
                    context,
                    alarm.instanceBegin,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_UTC,
                )
            } else {
                DateUtils.formatDateTime(
                    context,
                    alarm.instanceBegin,
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE,
                )
            }
        val text =
            listOfNotNull(time, alarm.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIcon())
                .setContentTitle(alarm.title ?: "Event")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .apply { content?.let(::setContentIntent) }
                .build()
        manager.notify(NOTIFICATION_BASE + alarm.id.toInt(), notification)
    }

    /** The app's notification icon if it has one, else a system icon. */
    private fun smallIcon(): Int {
        @Suppress("DiscouragedApi")
        val icon = context.resources.getIdentifier("notif_icon", "drawable", context.packageName)
        return if (icon != 0) icon else android.R.drawable.ic_popup_reminder
    }

    companion object {
        const val ACTION_REMINDER = "com.shub39.grit.calendar.REMINDER"
        private const val CHANNEL_ID = "calendar_events"
        private const val WATERMARK = "alarm_watermark"
        private const val MAX_LATE_MS = 60 * 60 * 1000L
        private const val NOTIFICATION_BASE = 0x4300000
    }
}
