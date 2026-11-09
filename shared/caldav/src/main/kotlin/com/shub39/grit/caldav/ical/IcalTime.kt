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
package com.shub39.grit.caldav.ical

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime

/** A DTSTART, DTEND, RECURRENCE-ID or EXDATE value, in the form iCalendar stores it. */
sealed interface IcalTime {
    /** An all-day date (`VALUE=DATE`). */
    data class Date(val date: LocalDate) : IcalTime

    /** A local date and time in a named time zone (`TZID=...`). */
    data class Zoned(val dateTime: LocalDateTime, val zoneId: String) : IcalTime

    /** An absolute time in UTC (`...Z`). */
    data class Utc(val instant: Instant) : IcalTime

    /** A local date and time without a time zone. It means the same wall-clock time everywhere. */
    data class Floating(val dateTime: LocalDateTime) : IcalTime

    val isAllDay: Boolean
        get() = this is Date
}

/** Converts an ical4j date value to [IcalTime]. [tzId] is the TZID parameter, if any. */
internal fun Temporal.toIcalTime(tzId: String?): IcalTime =
    when (this) {
        is java.time.LocalDate -> IcalTime.Date(toKotlinLocalDate())
        is java.time.LocalDateTime -> IcalTime.Floating(toKotlinLocalDateTime())
        is java.time.Instant -> IcalTime.Utc(toKotlinInstant())
        is OffsetDateTime -> IcalTime.Utc(toInstant().toKotlinInstant())
        is ZonedDateTime ->
            if (tzId == null || zone == ZoneOffset.UTC) IcalTime.Utc(toInstant().toKotlinInstant())
            else IcalTime.Zoned(toLocalDateTime().toKotlinLocalDateTime(), tzId)
        else -> throw IllegalArgumentException("Unsupported date value: $this")
    }

/** Resolves a TZID to a [ZoneId]. Unknown IDs fall back to [fallback]. */
internal fun zoneIdOf(tzId: String, fallback: ZoneId): ZoneId =
    try {
        ZoneId.of(tzId)
    } catch (_: Exception) {
        fallback
    }

/**
 * Converts to a java.time value that ical4j can write. All-day dates become `LocalDate`, floating
 * times `LocalDateTime`, UTC times `Instant`, and zoned times `ZonedDateTime`.
 */
internal fun IcalTime.toTemporal(): Temporal =
    when (this) {
        is IcalTime.Date -> date.toJavaLocalDate()
        is IcalTime.Floating -> dateTime.toJavaLocalDateTime()
        is IcalTime.Utc -> instant.toJavaInstant()
        is IcalTime.Zoned ->
            ZonedDateTime.of(dateTime.toJavaLocalDateTime(), zoneIdOf(zoneId, ZoneOffset.UTC))
    }

/**
 * The absolute start of this time. All-day dates and floating times use [deviceZone], because they
 * have no zone of their own.
 */
fun IcalTime.toInstant(deviceZone: ZoneId): Instant =
    when (this) {
        is IcalTime.Date -> date.toJavaLocalDate().atStartOfDay(deviceZone).toInstant()
        is IcalTime.Floating -> dateTime.toJavaLocalDateTime().atZone(deviceZone).toInstant()
        is IcalTime.Utc -> instant.toJavaInstant()
        is IcalTime.Zoned ->
            dateTime.toJavaLocalDateTime().atZone(zoneIdOf(zoneId, deviceZone)).toInstant()
    }.toKotlinInstant()
