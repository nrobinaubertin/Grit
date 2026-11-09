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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toJavaLocalDate

/**
 * Date and time texts in the device language. Formatters are cached per pattern and language,
 * because building one parses its pattern, and lists call these functions for every row.
 */
object Formatting {
    private val locale: Locale
        get() = Locale.getDefault()

    private val cache = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    private fun pattern(pattern: String): DateTimeFormatter =
        cache.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

    private fun localized(date: FormatStyle, time: FormatStyle?): DateTimeFormatter =
        cache.getOrPut("localized:$date:$time" to locale) {
            val formatter =
                if (time == null) DateTimeFormatter.ofLocalizedDate(date)
                else DateTimeFormatter.ofLocalizedDateTime(date, time)
            formatter.withLocale(locale)
        }

    private fun timePattern(is24Hr: Boolean) = pattern(if (is24Hr) "HH:mm" else "h:mm a")

    fun time(epochMillis: Long, zone: ZoneId, is24Hr: Boolean): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(timePattern(is24Hr))

    fun time(time: LocalTime, is24Hr: Boolean): String =
        java.time.LocalTime.of(time.hour, time.minute).format(timePattern(is24Hr))

    /** For example "Monday 9 November". */
    fun dayTitle(date: LocalDate): String = date.toJavaLocalDate().format(pattern("EEEE d MMMM"))

    fun dateMedium(date: LocalDate): String =
        date.toJavaLocalDate().format(localized(FormatStyle.MEDIUM, null))

    /** For example "November 2026". */
    fun month(month: YearMonth): String =
        java.time.YearMonth.of(month.year, month.month.ordinal + 1)
            .format(pattern("LLLL yyyy"))
            .replaceFirstChar { it.titlecase(locale) }

    /** Short weekday name, for example "Mon". */
    fun weekday(day: kotlinx.datetime.DayOfWeek): String =
        java.time.DayOfWeek.of(day.ordinal + 1)
            .getDisplayName(java.time.format.TextStyle.SHORT, locale)

    fun dateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(localized(FormatStyle.MEDIUM, FormatStyle.SHORT))

    /** All-day instances are stored at midnight UTC. */
    fun allDayDate(epochMillis: Long): java.time.LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
