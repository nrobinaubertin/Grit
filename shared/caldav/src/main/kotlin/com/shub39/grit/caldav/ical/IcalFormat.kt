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

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import kotlin.time.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

/** The parameters (VALUE, TZID) that a date property needs to store this time. */
internal fun IcalTime.parameters(): ParameterList =
    when (this) {
        is IcalTime.Date -> ParameterList(listOf(Value.DATE))
        is IcalTime.Zoned -> ParameterList(listOf(TzId(zoneId)))
        is IcalTime.Floating,
        is IcalTime.Utc -> ParameterList()
    }

/** The iCalendar text value of this time, for example `20260301T100000`. */
internal fun IcalTime.value(): String =
    when (this) {
        is IcalTime.Date -> date.toJavaLocalDate().format(DATE_FORMAT)
        is IcalTime.Zoned -> dateTime.toJavaLocalDateTime().format(DATE_TIME_FORMAT)
        is IcalTime.Floating -> dateTime.toJavaLocalDateTime().format(DATE_TIME_FORMAT)
        is IcalTime.Utc ->
            instant.toJavaInstant().atOffset(ZoneOffset.UTC).format(DATE_TIME_FORMAT) + "Z"
    }

/**
 * Builds a compact VTIMEZONE for [zoneId] from the current rules of the system time zone database.
 * RFC 5545 requires a VTIMEZONE for every TZID. A full history (as bundled with ical4j) is several
 * kilobytes per zone, so this keeps only the yearly rules.
 */
internal fun vTimeZoneText(zoneId: ZoneId): String {
    val rules = zoneId.rules
    val text = StringBuilder("BEGIN:VTIMEZONE\r\nTZID:${zoneId.id}\r\n")
    val transitionRules = rules.transitionRules
    if (transitionRules.isEmpty()) {
        val offset = rules.getOffset(java.time.Instant.now())
        text.observance("STANDARD", "19700101T000000", offset, offset, rrule = null)
    } else {
        for (rule in transitionRules) {
            val transition = rule.createTransition(1970)
            val kind =
                if (rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds) "DAYLIGHT"
                else "STANDARD"
            text.observance(
                kind,
                transition.dateTimeBefore.format(DATE_TIME_FORMAT),
                rule.offsetBefore,
                rule.offsetAfter,
                yearlyRule(rule),
            )
        }
    }
    text.append("END:VTIMEZONE\r\n")
    return text.toString()
}

private fun StringBuilder.observance(
    kind: String,
    start: String,
    from: ZoneOffset,
    to: ZoneOffset,
    rrule: String?,
) {
    append("BEGIN:$kind\r\n")
    append("DTSTART:$start\r\n")
    append("TZOFFSETFROM:${offset(from)}\r\n")
    append("TZOFFSETTO:${offset(to)}\r\n")
    if (rrule != null) append("RRULE:$rrule\r\n")
    append("END:$kind\r\n")
}

private fun offset(offset: ZoneOffset): String {
    val total = offset.totalSeconds
    val sign = if (total < 0) "-" else "+"
    val abs = kotlin.math.abs(total)
    val hours = abs / 3600
    val minutes = abs % 3600 / 60
    return sign + "%02d%02d".format(hours, minutes)
}

/** Converts a java.time transition rule to an equivalent yearly RRULE. */
private fun yearlyRule(rule: ZoneOffsetTransitionRule): String {
    val month = rule.month.value
    val dayOfWeek = rule.dayOfWeek
    val indicator = rule.dayOfMonthIndicator
    if (dayOfWeek == null) return "FREQ=YEARLY;BYMONTH=$month;BYMONTHDAY=$indicator"
    // "First <weekday> on or after day N" is the weekday among days N..N+6. A negative N counts
    // from the end of the month, and means "on or before".
    val days =
        if (indicator > 0) (indicator..minOf(indicator + 6, 31)).toList()
        else (indicator downTo indicator - 6).toList()
    return "FREQ=YEARLY;BYMONTH=$month;BYDAY=${dayOfWeek.icalName()};BYMONTHDAY=" +
        days.joinToString(",")
}

private fun DayOfWeek.icalName(): String = name.take(2)
