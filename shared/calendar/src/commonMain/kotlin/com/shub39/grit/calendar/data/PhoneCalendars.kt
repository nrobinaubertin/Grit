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

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/** A calendar that another app keeps in the calendar storage of the phone. */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    /** The account of the app that owns the calendar, for example an email address. */
    val accountName: String?,
    /** ARGB color. */
    val color: Int,
)

/**
 * One row of the Android calendar provider's event table, with its reminders. Times are UTC
 * milliseconds. An all-day event starts and ends at midnight UTC.
 */
data class PhoneEventRow(
    val id: Long,
    /** The iCalendar UID, if the owning app keeps one. */
    val uid: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val start: Long,
    val end: Long?,
    /** RFC 2445 duration, used by repeating events instead of [end], for example "P3600S". */
    val duration: String?,
    val allDay: Boolean,
    val timeZone: String?,
    val rrule: String?,
    val rdate: String?,
    val exrule: String?,
    val exdate: String?,
    /** For a changed or cancelled occurrence: the row of its series, and its original start. */
    val originalId: Long? = null,
    val originalInstanceTime: Long? = null,
    val originalAllDay: Boolean = false,
    /** CalendarContract status: 0 tentative, 1 confirmed, 2 cancelled. */
    val status: Int? = null,
    /** Minutes before the start, for the reminders that the phone shows. */
    val reminderMinutes: List<Int> = emptyList(),
)

/**
 * Reads the calendar storage of the phone (the Android calendar provider), where apps such as
 * Google Calendar or DAVx5 keep their calendars. Grit only reads it, to import a calendar. The
 * desktop app has none.
 */
@Single
expect class PhoneCalendarSource {
    /** False on platforms without a calendar storage. */
    val supported: Boolean

    fun hasPermission(): Boolean

    /** Emits at once, then after changes in the calendar storage. Slow collectors get one. */
    fun changes(): Flow<Unit>

    suspend fun calendars(): List<PhoneCalendar>

    /** All events of a calendar, with their reminders. Deleted rows are left out. */
    suspend fun events(calendarId: Long): List<PhoneEventRow>
}

/** The calendars of other apps on the phone, which the user can import into Grit. */
@Single
class PhoneCalendars(private val source: PhoneCalendarSource) {
    val supported: Boolean
        get() = source.supported

    fun hasPermission(): Boolean = source.hasPermission()

    /** The calendars of the phone. Empty without the permission. */
    fun calendars(): Flow<List<PhoneCalendar>> =
        if (!source.hasPermission()) flowOf(emptyList())
        else source.changes().map { source.calendars() }

    /** A calendar of the phone as iCalendar text, ready for [EventResource.splitFile]. */
    suspend fun exportIcs(calendarId: Long, now: Instant = Instant.now()): String =
        PhoneIcs.build(calendarId, source.events(calendarId), now)
}

/**
 * Turns the rows of the Android calendar provider into iCalendar text. A changed or cancelled
 * occurrence becomes an override with RECURRENCE-ID and the UID of its series, so that the import
 * keeps the series together.
 */
object PhoneIcs {
    private val utcDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val localDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val date = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun build(calendarId: Long, rows: List<PhoneEventRow>, now: Instant): String {
        val byId = rows.associateBy { it.id }
        val stamp = utcDateTime.format(now.atOffset(ZoneOffset.UTC))
        fun uidOf(row: PhoneEventRow): String =
            row.uid?.takeIf { it.isNotBlank() } ?: "android-$calendarId-${row.id}@grit"
        return buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//Phone import//EN\r\n")
            for (row in rows) {
                val series = row.originalId?.let(byId::get)
                // An occurrence without its series in this calendar stands on its own
                val override = series != null && row.originalInstanceTime != null
                // A cancelled series has nothing to import
                if (!override && row.status == STATUS_CANCELLED) continue
                append("BEGIN:VEVENT\r\n")
                line("UID", uidOf(if (override) series!! else row))
                line("DTSTAMP", stamp)
                append(dateLine("DTSTART", row.start, row.allDay, row.timeZone))
                endMillis(row)?.let { append(dateLine("DTEND", it, row.allDay, row.timeZone)) }
                if (override) {
                    append(
                        dateLine(
                            "RECURRENCE-ID",
                            row.originalInstanceTime!!,
                            row.originalAllDay,
                            series!!.timeZone,
                        )
                    )
                } else {
                    row.rrule?.takeIf { it.isNotBlank() }?.let { line("RRULE", it) }
                    row.exrule?.takeIf { it.isNotBlank() }?.let { line("EXRULE", it) }
                    row.rdate?.let { append(dateListLine("RDATE", it, row.allDay)) }
                    row.exdate?.let { append(dateListLine("EXDATE", it, row.allDay)) }
                }
                row.title?.takeIf { it.isNotBlank() }?.let { line("SUMMARY", escape(it)) }
                row.location?.takeIf { it.isNotBlank() }?.let { line("LOCATION", escape(it)) }
                row.description?.takeIf { it.isNotBlank() }?.let { line("DESCRIPTION", escape(it)) }
                when (row.status) {
                    0 -> line("STATUS", "TENTATIVE")
                    1 -> line("STATUS", "CONFIRMED")
                    STATUS_CANCELLED -> line("STATUS", "CANCELLED")
                }
                for (minutes in row.reminderMinutes.filter { it >= 0 }.distinct()) {
                    append("BEGIN:VALARM\r\nACTION:DISPLAY\r\n")
                    line("DESCRIPTION", escape(row.title ?: "Event"))
                    line("TRIGGER", "-PT${minutes}M")
                    append("END:VALARM\r\n")
                }
                append("END:VEVENT\r\n")
            }
            append("END:VCALENDAR\r\n")
        }
    }

    /** DTEND, or the start plus the duration that repeating events use instead. */
    private fun endMillis(row: PhoneEventRow): Long? =
        row.end?.takeIf { it >= row.start }
            ?: row.duration?.let(::durationMillis)?.let { row.start + it }

    /**
     * An RFC 2445 or 5545 duration in milliseconds, with or without "T": "P3600S", "PT1H", "P1D".
     */
    internal fun durationMillis(text: String): Long? {
        val match =
            Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
                .matchEntire(text.trim().uppercase()) ?: return null
        val (sign, weeks, days, hours, minutes, seconds) = match.destructured
        fun part(value: String, unit: Long) = (value.toLongOrNull() ?: 0L) * unit
        val total =
            part(weeks, 7 * 86_400_000L) +
                part(days, 86_400_000L) +
                part(hours, 3_600_000L) +
                part(minutes, 60_000L) +
                part(seconds, 1_000L)
        return if (sign == "-") -total else total
    }

    private fun dateLine(name: String, millis: Long, allDay: Boolean, timeZone: String?): String {
        val instant = Instant.ofEpochMilli(millis)
        val zone = zoneOrNull(timeZone)
        return when {
            allDay -> "$name;VALUE=DATE:${date.format(instant.atOffset(ZoneOffset.UTC))}\r\n"
            zone == null || zone.normalized() == ZoneOffset.UTC ->
                "$name:${utcDateTime.format(instant.atOffset(ZoneOffset.UTC))}\r\n"
            else -> "$name;TZID=${zone.id}:${localDateTime.format(instant.atZone(zone))}\r\n"
        }
    }

    /**
     * RDATE or EXDATE. The provider keeps comma-separated UTC times, sometimes with a "TZID;"
     * prefix. All-day series need dates: a time would never match their occurrences.
     */
    private fun dateListLine(name: String, value: String, allDay: Boolean): String {
        val zone = value.substringBefore(';', "").takeIf { ';' in value }
        val dates =
            value.substringAfter(';').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (dates.isEmpty()) return ""
        return when {
            allDay -> "$name;VALUE=DATE:${dates.joinToString(",") { it.take(8) }}\r\n"
            zone != null -> "$name;TZID=$zone:${dates.joinToString(",")}\r\n"
            else -> "$name:${dates.joinToString(",")}\r\n"
        }
    }

    private fun zoneOrNull(id: String?): ZoneId? =
        id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun StringBuilder.line(name: String, value: String) {
        append(name).append(':').append(value).append("\r\n")
    }

    /** RFC 5545 text escaping. */
    private fun escape(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")

    private const val STATUS_CANCELLED = 2
}
