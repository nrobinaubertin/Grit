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
    /**
     * Lines from the top of the event area of the cell. Bars over several days or a whole day are
     * on whole lines, at the top. Other events are lower for a later time, so they can be between
     * two lines.
     */
    val position: Float,
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

/** The events of each day of one week, and the boxes of the hour overview. */
@Immutable
data class WeekData(val days: List<List<DayEvent>>, val boxes: List<TimedBox>) {
    companion object {
        val Empty = WeekData(List(7) { emptyList() }, emptyList())
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** All-day events and events of 24 hours or more stay out of the hour overview of the week. */
fun DayEvent.isBandEvent(): Boolean = allDay || end - begin >= DAY_MS

/**
 * The part of the day that the height of a month cell shows. Events before it are at the top of the
 * cell, events after it at the bottom.
 */
private const val CELL_DAY_START = 6 * 60
private const val CELL_DAY_END = 22 * 60

/**
 * Places the events of a week in the month grid. Multi-day and all-day events come first, as bars
 * on whole lines that keep one line over all their days, like the month view of most calendar apps.
 * The other events of a day go below these bars, lower for a later start: the top of the cell is
 * the morning, the bottom is the evening. A day with more events than [maxLanes] shows [maxLanes] -
 * 1 events, and the last line counts the hidden ones.
 */
fun layoutWeek(
    weekStart: LocalDate,
    events: List<DayEvent>,
    maxLanes: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): WeekLanes {
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
    val (bars, timed) = candidates.partition { it.allDay || it.endDay > it.startDay }
    fun column(date: LocalDate) =
        (date.toEpochDays() - weekStart.toEpochDays()).toInt().coerceIn(0, 6)
    val timedByColumn = timed.groupBy { column(it.startDay) }

    val total = IntArray(7)
    for (event in candidates) {
        for (c in column(event.startDay)..column(event.endDay)) total[c]++
    }
    // Days with too many events keep their last line for "+N"
    val capacity = IntArray(7) { if (total[it] > maxLanes) maxLanes - 1 else maxLanes }

    repeat(3) {
        val occupied = Array(maxLanes) { BooleanArray(7) }
        val segments = mutableListOf<Segment>()
        val hidden = IntArray(7)
        for (event in bars) {
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
                    position = lane.toFloat(),
                    continuesBefore = event.startDay < weekStart,
                    continuesAfter = event.endDay > weekEnd,
                )
        }
        for ((c, dayEvents) in timedByColumn) {
            // Below the lowest bar of the day
            val top = (maxLanes - 1 downTo 0).firstOrNull { occupied[it][c] }?.plus(1) ?: 0
            val shown = dayEvents.take((capacity[c] - top).coerceAtLeast(0))
            hidden[c] += dayEvents.size - shown.size
            val positions = placeByTime(shown.map { dayFraction(it.begin, zone) }, top, capacity[c])
            for ((event, position) in shown.zip(positions)) {
                segments += Segment(event, c, c, position, false, false)
            }
        }
        // A day that hides events needs its last line free for "+N"
        val needsRoom = (0 until 7).filter { hidden[it] > 0 && capacity[it] == maxLanes }
        if (needsRoom.isEmpty()) return WeekLanes(segments, hidden.toList())
        for (c in needsRoom) capacity[c] = maxLanes - 1
    }
    return WeekLanes.Empty
}

/** Where [begin] falls in the part of the day that a month cell shows, from 0 to 1. */
private fun dayFraction(begin: Long, zone: ZoneId): Float {
    val time = java.time.Instant.ofEpochMilli(begin).atZone(zone)
    val minute = time.hour * 60 + time.minute
    return ((minute - CELL_DAY_START).toFloat() / (CELL_DAY_END - CELL_DAY_START)).coerceIn(0f, 1f)
}

/**
 * Positions for events at the day [fractions] (in order), between the lines [top] and [bottom]
 * (exclusive): each at the place of its time, and one line apart at least. There must be room for
 * all of them.
 */
internal fun placeByTime(fractions: List<Float>, top: Int, bottom: Int): List<Float> {
    val span = (bottom - top - 1).coerceAtLeast(0)
    val positions = FloatArray(fractions.size) { top + fractions[it] * span }
    for (i in 1 until positions.size) {
        positions[i] = maxOf(positions[i], positions[i - 1] + 1f)
    }
    for (i in positions.indices.reversed()) {
        val limit = if (i == positions.lastIndex) bottom - 1f else positions[i + 1] - 1f
        positions[i] = minOf(positions[i], limit)
    }
    return positions.toList()
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
