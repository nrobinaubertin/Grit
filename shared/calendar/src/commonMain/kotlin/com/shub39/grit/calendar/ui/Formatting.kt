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

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toJavaLocalDate

/** Date and time texts in the device language. */
object Formatting {
    private val locale: Locale
        get() = Locale.getDefault()

    fun time(epochMillis: Long, zone: ZoneId, is24Hr: Boolean): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern(if (is24Hr) "HH:mm" else "h:mm a", locale))

    fun time(time: LocalTime, is24Hr: Boolean): String =
        java.time.LocalTime.of(time.hour, time.minute)
            .format(DateTimeFormatter.ofPattern(if (is24Hr) "HH:mm" else "h:mm a", locale))

    /** For example "Monday 9 November". */
    fun dayTitle(date: LocalDate): String =
        date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))

    fun dateMedium(date: LocalDate): String =
        date
            .toJavaLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    /** For example "November 2026". */
    fun month(month: YearMonth): String =
        java.time.YearMonth.of(month.year, month.month.ordinal + 1)
            .format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }

    /** Short weekday name, for example "Mon". */
    fun weekday(day: kotlinx.datetime.DayOfWeek): String =
        java.time.DayOfWeek.of(day.ordinal + 1)
            .getDisplayName(java.time.format.TextStyle.SHORT, locale)

    fun dateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withLocale(locale)
            )

    /** All-day instances are stored at midnight UTC. */
    fun allDayDate(epochMillis: Long): java.time.LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
