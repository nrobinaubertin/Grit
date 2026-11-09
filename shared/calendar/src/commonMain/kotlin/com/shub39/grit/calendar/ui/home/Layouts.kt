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
package com.shub39.grit.calendar.ui.home

import androidx.compose.runtime.Immutable
import java.time.ZoneId
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toKotlinTimeZone

/** One bar of an event in a week row. A multi-day event is one bar over its days. */
@Immutable
data class Segment(
    val event: DayEvent,
    val startColumn: Int,
    val endColumn: Int,
    val lane: Int,
    /** The event started before this week: the bar has no left end. */
    val continuesBefore: Boolean,
    /** The event goes on after this week: the bar has no right end. */
    val continuesAfter: Boolean,
)

/** Bars of one week row, and per day the number of events that did not fit. */
@Immutable
data class WeekLanes(val segments: List<Segment>, val hidden: List<Int>) {
    companion object {
        val Empty = WeekLanes(emptyList(), List(7) { 0 })
    }
}

/** An event box in the hour grid of the week view, for one day. */
@Immutable
data class TimedBox(
    val event: DayEvent,
    val column: Int,
    val startMinute: Int,
    val endMinute: Int,
    /** Position among the events that overlap it, and their number. */
    val slot: Int,
    val slots: Int,
)

/** The all-day band and the hour boxes of one week. */
@Immutable
data class WeekData(val band: WeekLanes, val boxes: List<TimedBox>) {
    companion object {
        val Empty = WeekData(WeekLanes.Empty, emptyList())
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** All-day events and events of 24 hours or more go in the band above the hour grid. */
fun DayEvent.isBandEvent(): Boolean = allDay || end - begin >= DAY_MS

/**
 * Places the events of a week in lanes, like the month view of most calendar apps. Multi-day events
 * come first and keep one lane over all their days. A day with more events than [maxLanes] shows
 * [maxLanes] - 1 events, and the last lane counts the hidden ones.
 */
fun layoutWeek(weekStart: LocalDate, events: List<DayEvent>, maxLanes: Int): WeekLanes {
    val weekEnd = weekStart.plus(DatePeriod(days = 6))
    val candidates =
        events
            .filter { it.endDay >= weekStart && it.startDay <= weekEnd }
            .sortedWith(
                compareByDescending<DayEvent> { it.endDay > it.startDay }
                    .thenByDescending { it.endDay.toEpochDays() - it.startDay.toEpochDays() }
                    .thenByDescending { it.allDay }
                    .thenBy { it.begin }
                    .thenByDescending { it.end }
            )
    fun column(date: LocalDate) =
        (date.toEpochDays() - weekStart.toEpochDays()).toInt().coerceIn(0, 6)

    val total = IntArray(7)
    for (event in candidates) {
        for (c in column(event.startDay)..column(event.endDay)) total[c]++
    }
    // Days with too many events keep their last lane for "+N"
    val capacity = IntArray(7) { if (total[it] > maxLanes) maxLanes - 1 else maxLanes }

    repeat(3) {
        val occupied = Array(maxLanes) { BooleanArray(7) }
        val segments = mutableListOf<Segment>()
        val hidden = IntArray(7)
        for (event in candidates) {
            val start = column(event.startDay)
            val end = column(event.endDay)
            val limit = (start..end).minOf { capacity[it] }
            val lane =
                (0 until limit).firstOrNull { lane -> (start..end).none { occupied[lane][it] } }
            if (lane == null) {
                for (c in start..end) hidden[c]++
                continue
            }
            for (c in start..end) occupied[lane][c] = true
            segments +=
                Segment(
                    event = event,
                    startColumn = start,
                    endColumn = end,
                    lane = lane,
                    continuesBefore = event.startDay < weekStart,
                    continuesAfter = event.endDay > weekEnd,
                )
        }
        // A day that hides events needs its last lane free for "+N"
        val needsRoom =
            (0 until 7).filter {
                hidden[it] > 0 && capacity[it] == maxLanes && occupied[maxLanes - 1][it]
            }
        if (needsRoom.isEmpty()) return WeekLanes(segments, hidden.toList())
        for (c in needsRoom) capacity[c] = maxLanes - 1
    }
    return WeekLanes.Empty
}

/**
 * Places the timed events of a week in the hour grid. Events that overlap share the width of the
 * day. Events that cross midnight get one box per day.
 */
fun layoutTimed(weekStart: LocalDate, events: List<DayEvent>, zone: ZoneId): List<TimedBox> {
    val timeZone = zone.toKotlinTimeZone()
    val result = mutableListOf<TimedBox>()
    for (column in 0 until 7) {
        val day = weekStart.plus(DatePeriod(days = column))
        val dayStart = day.atStartOfDayIn(timeZone).toEpochMilliseconds()
        val dayEnd = day.plus(DatePeriod(days = 1)).atStartOfDayIn(timeZone).toEpochMilliseconds()
        val parts =
            events
                .filter {
                    !it.isBandEvent() &&
                        it.begin < dayEnd &&
                        (it.end > dayStart || (it.end == it.begin && it.begin >= dayStart))
                }
                .map { event ->
                    val start = ((maxOf(event.begin, dayStart) - dayStart) / 60_000).toInt()
                    val end = ((minOf(event.end, dayEnd) - dayStart) / 60_000).toInt()
                    Triple(event, start, maxOf(end, start + MIN_BOX_MINUTES))
                }
                .sortedWith(compareBy({ it.second }, { -it.third }))

        // Clusters of overlapping events; each event takes the first free slot of its cluster
        var cluster = mutableListOf<Pair<Triple<DayEvent, Int, Int>, Int>>()
        var clusterEnd = -1
        fun flush() {
            val slots = (cluster.maxOfOrNull { it.second } ?: -1) + 1
            for ((part, slot) in cluster) {
                result += TimedBox(part.first, column, part.second, part.third, slot, slots)
            }
            cluster = mutableListOf()
        }
        for (part in parts) {
            if (part.second >= clusterEnd) {
                flush()
                clusterEnd = part.third
            } else {
                clusterEnd = maxOf(clusterEnd, part.third)
            }
            val used = cluster.filter { it.first.third > part.second }.map { it.second }.toSet()
            val slot = generateSequence(0) { it + 1 }.first { it !in used }
            cluster += part to slot
        }
        flush()
    }
    return result
}

/** Short events still get a box tall enough to tap. */
private const val MIN_BOX_MINUTES = 20
