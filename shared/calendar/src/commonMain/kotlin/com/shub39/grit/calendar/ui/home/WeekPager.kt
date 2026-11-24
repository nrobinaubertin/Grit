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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shub39.grit.calendar.ui.Formatting
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Weeks on each side of the current week that the pager can reach. */
private const val PAGE_RANGE = 52 * 10

/** Hours that the overview always shows. Events outside them make it longer. */
private const val OVERVIEW_FIRST_HOUR = 8
private const val OVERVIEW_LAST_HOUR = 20

/** Most lines of one event in a day cell. */
private const val EVENT_MAX_LINES = 3

/**
 * Swipeable week view, like a paper planner: two columns of four cells. The first four days are on
 * the left, the last three on the right, and the eighth cell is an overview of the hours of the
 * week. Each day cell lists its events with their start time. Every part is one drawing, like the
 * month grid, so that slow phones keep up.
 */
@Composable
internal fun WeekPager(
    week: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    weeks: Map<LocalDate, WeekData>,
    is24Hr: Boolean,
    canCreate: Boolean,
    onWeekChange: (LocalDate) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember(firstDayOfWeek) { startOfWeek(today, firstDayOfWeek) }
    fun pageOf(start: LocalDate) =
        (PAGE_RANGE + ((start.toEpochDays() - origin.toEpochDays()) / 7).toInt()).coerceIn(
            0,
            2 * PAGE_RANGE,
        )
    val pagerState = rememberPagerState(initialPage = pageOf(week)) { 2 * PAGE_RANGE + 1 }
    val currentOnWeekChange by rememberUpdatedState(onWeekChange)

    LaunchedEffect(pagerState, origin) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { currentOnWeekChange(origin.plus(DatePeriod(days = 7 * (it - PAGE_RANGE)))) }
    }
    LaunchedEffect(week) {
        val target = pageOf(week)
        if (pagerState.settledPage != target) {
            if (abs(pagerState.settledPage - target) <= 2) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
    }

    // The current time line moves once a minute
    val nowMinute by
        produceState(currentMinute()) {
            while (true) {
                delay(60_000L - System.currentTimeMillis() % 60_000L)
                value = currentMinute()
            }
        }

    // The previous and next pages are built a few frames after the first one
    var neighbours by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos {} }
        neighbours = 1
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = neighbours,
        key = { it },
        modifier = modifier,
    ) { page ->
        val start = origin.plus(DatePeriod(days = 7 * (page - PAGE_RANGE)))
        WeekPage(
            start = start,
            today = today,
            data = weeks[start] ?: WeekData.Empty,
            is24Hr = is24Hr,
            canCreate = canCreate,
            nowMinute = nowMinute,
            onSelect = onSelect,
            onOpenEvent = onOpenEvent,
            onCreateAt = onCreateAt,
        )
    }
}

private fun currentMinute(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

/** The cell of the day at [index] in the week: four on the left, then three on the right. */
private fun cellOf(index: Int): Pair<Int, Int> = if (index < 4) 0 to index else 1 to index - 4

/** The day under [offset] in a page of [size], or -1 for the overview. */
private fun dayAt(offset: Offset, size: IntSize): Int {
    val column = if (offset.x < size.width / 2f) 0 else 1
    val row = (offset.y / (size.height / 4f)).toInt().coerceIn(0, 3)
    return when {
        column == 0 -> row
        row == 3 -> -1
        else -> 4 + row
    }
}

private fun LocalDate.isWeekend() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

/** Where the events of the shown page are, so that a tap finds the event under it. */
private class EventHits {
    var areas: List<Pair<Rect, DayEvent>> = emptyList()
}

@Composable
private fun WeekPage(
    start: LocalDate,
    today: LocalDate,
    data: WeekData,
    is24Hr: Boolean,
    canCreate: Boolean,
    nowMinute: Int,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
) {
    val days = remember(start) { List(7) { start.plus(DatePeriod(days = it)) } }
    val hits = remember { EventHits() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpen by rememberUpdatedState(onOpenEvent)
    val currentOnCreate by rememberUpdatedState(onCreateAt)
    val currentCanCreate by rememberUpdatedState(canCreate)
    Box(
        modifier =
            Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, bottom = 4.dp).pointerInput(
                days
            ) {
                detectTapGestures { offset ->
                    val hit = hits.areas.lastOrNull { it.first.contains(offset) }
                    if (hit != null) return@detectTapGestures currentOnOpen(hit.second)
                    val index = dayAt(offset, size)
                    if (index < 0) return@detectTapGestures
                    // A tap beside the events creates one on that day
                    if (currentCanCreate) currentOnCreate(days[index], null)
                    else currentOnSelect(days[index])
                }
            }
    ) {
        DayCells(days, today, data.days, is24Hr, hits, Modifier.matchParentSize())
        HourOverview(
            days = days,
            today = today,
            boxes = data.boxes,
            nowMinute = nowMinute,
            canCreate = canCreate,
            onSelect = onSelect,
            onOpenEvent = onOpenEvent,
            onCreateAt = onCreateAt,
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).fillMaxHeight(0.25f),
        )
    }
}

/** One event as it is drawn in a day cell. */
private class EventLine(
    val event: DayEvent,
    val layout: TextLayoutResult,
    val topLeft: Offset,
    val color: Color,
)

/** The seven day cells: day number and name, then the events of the day. */
@Composable
private fun DayCells(
    days: List<LocalDate>,
    today: LocalDate,
    events: List<List<DayEvent>>,
    is24Hr: Boolean,
    hits: EventHits,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val cells = rememberCellColors()
    val painter = remember { GridTextPainter() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Spacer(
        modifier =
            // Own layer: the recorded drawing is reused until the week or its events change
            modifier.graphicsLayer().drawWithCache {
                val zone = ZoneId.systemDefault()
                val cellWidth = size.width / 2
                val cellHeight = size.height / 4
                val pad = 6.dp.toPx()
                val numberSize = 20.sp.toPx()
                val nameSize = 14.sp.toPx()
                val headerBaseline = pad + numberSize * 0.95f
                val eventsTop = headerBaseline + numberSize * 0.35f + 2.dp.toPx()
                val style = TextStyle(fontSize = 12.sp, lineHeight = 15.sp)
                val lineHeight = 15.sp.toPx()
                val swatch = Size(10.dp.toPx(), 8.dp.toPx())
                val indent = swatch.width + 4.dp.toPx()
                val gap = 2.dp.toPx()
                val moreSize = 11.sp.toPx()
                val moreHeight = moreSize * 1.4f
                val corner = CornerRadius(2.dp.toPx())

                // Measure every event once; the drawing below only paints
                val lines = mutableListOf<EventLine>()
                val hidden = IntArray(7)
                for ((index, date) in days.withIndex()) {
                    val (column, row) = cellOf(index)
                    val left = column * cellWidth + pad
                    var y = row * cellHeight + eventsTop
                    val bottom = (row + 1) * cellHeight - pad / 2
                    val fill = if (date.isWeekend()) cells.weekend else cells.day
                    val dayEvents = events.getOrElse(index) { emptyList() }
                    for ((k, event) in dayEvents.withIndex()) {
                        // Room for the "+N" line while more events follow
                        val reserve = if (k < dayEvents.lastIndex) moreHeight else 0f
                        val maxLines =
                            minOf(
                                EVENT_MAX_LINES,
                                floor((bottom - reserve - y) / lineHeight).toInt(),
                            )
                        if (maxLines < 1) {
                            hidden[index] = dayEvents.size - k
                            break
                        }
                        // Tasks start with their check box instead of the calendar pattern
                        val x = if (event.taskId != null) left else left + indent
                        val layout =
                            measurer.measure(
                                text = eventText(event, date, zone, is24Hr),
                                style = style,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = maxLines,
                                constraints =
                                    Constraints(
                                        maxWidth =
                                            (column * cellWidth + cellWidth - pad - x)
                                                .toInt()
                                                .coerceAtLeast(1)
                                    ),
                            )
                        lines +=
                            EventLine(
                                event = event,
                                layout = layout,
                                topLeft = Offset(x, y),
                                color = readable(eventColor(event.color), fill, colors.onSurface),
                            )
                        y += layout.size.height + gap
                    }
                }
                hits.areas =
                    lines.map { line ->
                        val left = if (line.topLeft.x < cellWidth) 0f else cellWidth
                        Rect(
                            left,
                            line.topLeft.y - gap / 2,
                            left + cellWidth,
                            line.topLeft.y + line.layout.size.height + gap / 2,
                        ) to line.event
                    }

                onDrawBehind {
                    for ((index, date) in days.withIndex()) {
                        val (column, row) = cellOf(index)
                        val left = column * cellWidth
                        val top = row * cellHeight
                        val isToday = date == today
                        drawCell(
                            cells = cells,
                            fill = if (date.isWeekend()) cells.weekend else cells.day,
                            isToday = isToday,
                            topLeft = Offset(left, top),
                            size = Size(cellWidth, cellHeight),
                        )
                        val number = date.day.toString()
                        painter.draw(
                            scope = this,
                            text = number,
                            x = left + pad,
                            baseline = top + headerBaseline,
                            clipRight = left + cellWidth - pad,
                            color = if (isToday) colors.primary else colors.onSurface,
                            sizePx = numberSize,
                            bold = true,
                        )
                        // The month after the name of the first day, and of the first of a month
                        val name =
                            Formatting.weekdayLong(date.dayOfWeek) +
                                if (index == 0 || date.day == 1)
                                    " / " + Formatting.monthShort(date.month).uppercase()
                                else ""
                        painter.draw(
                            scope = this,
                            text = name,
                            x =
                                left +
                                    pad +
                                    painter.width(number, numberSize, bold = true) +
                                    5.dp.toPx(),
                            baseline = top + headerBaseline,
                            clipRight = left + cellWidth - pad,
                            color = colors.primary,
                            sizePx = nameSize,
                            bold = isToday,
                        )
                        if (hidden[index] > 0) {
                            painter.draw(
                                scope = this,
                                text = "+${hidden[index]}",
                                x = left + pad,
                                baseline = top + cellHeight - pad / 2 - moreSize * 0.4f,
                                clipRight = left + cellWidth - pad,
                                color = colors.onSurfaceVariant,
                                sizePx = moreSize,
                                bold = true,
                            )
                        }
                    }
                    for (line in lines) {
                        if (line.event.taskId == null) {
                            val firstLine = line.layout.getLineBottom(0) - line.layout.getLineTop(0)
                            drawEventBlock(
                                pattern = line.event.pattern,
                                color = line.color,
                                topLeft =
                                    Offset(
                                        line.topLeft.x - indent,
                                        line.topLeft.y + (firstLine - swatch.height) / 2,
                                    ),
                                size = swatch,
                                corner = corner,
                                tint = 0.7f,
                                bar = 0f,
                                background = Color.Transparent,
                            )
                        }
                        drawText(line.layout, color = line.color, topLeft = line.topLeft)
                    }
                }
            }
    )
}

/** "20:00 Title" on the day an event starts, the title alone on its other days. */
private fun eventText(
    event: DayEvent,
    date: LocalDate,
    zone: ZoneId,
    is24Hr: Boolean,
): AnnotatedString = buildAnnotatedString {
    if (!event.allDay && event.startDay == date) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(Formatting.time(event.begin, zone, is24Hr))
        }
        append(" ")
    }
    append(event.title.ifBlank { "—" })
}

/**
 * [color], moved towards [text] until it stands out from [background] enough to read: category
 * colors are chosen for blocks, and some are too light or too dark for text.
 */
private fun readable(color: Color, background: Color, text: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }
    var result = color
    var mix = 0f
    while (contrast(result, background) < 3f && mix < 0.8f) {
        mix += 0.2f
        result = lerp(color, text, mix)
    }
    return result
}

/**
 * The eighth cell: the days of the week side by side, with the hours from top to bottom and a block
 * per event, to see the busy and free times at a glance. A tap on a block opens its event, a tap
 * beside them creates an event at that hour.
 */
@Composable
private fun HourOverview(
    days: List<LocalDate>,
    today: LocalDate,
    boxes: List<TimedBox>,
    nowMinute: Int,
    canCreate: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int?) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val cells = rememberCellColors()
    val painter = remember { GridTextPainter() }
    val firstHour =
        remember(boxes) {
            minOf(OVERVIEW_FIRST_HOUR, boxes.minOfOrNull { it.startMinute / 60 } ?: 24)
        }
    val lastHour =
        remember(boxes) {
            maxOf(OVERVIEW_LAST_HOUR, boxes.maxOfOrNull { (it.endMinute + 59) / 60 } ?: 0)
        }
    val labels =
        remember(days) {
            days.map { Formatting.weekdayLong(it.dayOfWeek).take(1) + it.day.toString() }
        }
    val todayColumn = days.indexOf(today)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpen by rememberUpdatedState(onOpenEvent)
    val currentOnCreate by rememberUpdatedState(onCreateAt)
    val currentCanCreate by rememberUpdatedState(canCreate)

    /** The grid of hours inside the cell, below the band of day labels. */
    fun grid(width: Float, height: Float, gap: Float, header: Float) =
        Rect(gap, gap + header, width - gap, height - gap)

    Box(
        modifier =
            modifier.pointerInput(days, boxes, firstHour, lastHour) {
                detectTapGestures { offset ->
                    val area =
                        grid(size.width.toFloat(), size.height.toFloat(), 1.dp.toPx(), 18.dp.toPx())
                    val columnWidth = area.width / 7
                    val column = ((offset.x - area.left) / columnWidth).toInt().coerceIn(0, 6)
                    if (offset.y < area.top) return@detectTapGestures currentOnSelect(days[column])
                    val hourHeight = area.height / (lastHour - firstHour)
                    val minute = firstHour * 60 + ((offset.y - area.top) / hourHeight * 60).toInt()
                    val hit =
                        boxes.lastOrNull { box ->
                            val slotWidth = columnWidth / box.slots
                            val slotLeft =
                                area.left + box.column * columnWidth + box.slot * slotWidth
                            box.column == column &&
                                minute in box.startMinute until box.endMinute &&
                                offset.x >= slotLeft &&
                                offset.x < slotLeft + slotWidth
                        }
                    when {
                        hit != null -> currentOnOpen(hit.event)
                        currentCanCreate ->
                            currentOnCreate(days[column], (minute / 60).coerceIn(0, 23))
                        else -> currentOnSelect(days[column])
                    }
                }
            }
    ) {
        Spacer(
            modifier =
                Modifier.matchParentSize().graphicsLayer().drawWithCache {
                    val gap = 1.dp.toPx()
                    val header = 18.dp.toPx()
                    val area = grid(size.width, size.height, gap, header)
                    val columnWidth = area.width / 7
                    val hourHeight = area.height / (lastHour - firstHour)
                    val labelSize = 11.sp.toPx()
                    val hourSize = 8.sp.toPx()
                    val corner = CornerRadius(8.dp.toPx())
                    val stripe = colors.onSurface.copy(alpha = 0.05f)
                    val line = colors.outlineVariant.copy(alpha = 0.6f)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    onDrawBehind {
                        drawCell(cells, cells.day, false, Offset.Zero, size)
                        // The band of day labels, square at its bottom
                        drawRoundRect(
                            colors.secondaryContainer,
                            Offset(gap, gap),
                            Size(area.width, header),
                            corner,
                        )
                        drawRect(
                            colors.secondaryContainer,
                            Offset(gap, gap + header / 2),
                            Size(area.width, header / 2),
                        )
                        for ((index, label) in labels.withIndex()) {
                            painter.draw(
                                scope = this,
                                text = label,
                                x = area.left + columnWidth * (index + 0.5f),
                                baseline = gap + header / 2 + labelSize * 0.36f,
                                clipRight = area.left + columnWidth * (index + 1),
                                color =
                                    if (index == todayColumn) colors.primary
                                    else colors.onSecondaryContainer,
                                sizePx = labelSize,
                                bold = index == todayColumn,
                                centered = true,
                            )
                        }
                        clipRect(area.left, area.top, area.right, area.bottom) {
                            for ((index, day) in days.withIndex()) {
                                if (!day.isWeekend()) continue
                                drawRect(
                                    cells.weekend,
                                    Offset(area.left + index * columnWidth, area.top),
                                    Size(columnWidth, area.height),
                                )
                            }
                            for (hour in firstHour until lastHour) {
                                val y = area.top + (hour - firstHour) * hourHeight
                                if (hour % 2 == 1) {
                                    drawRect(
                                        stripe,
                                        Offset(area.left, y),
                                        Size(area.width, hourHeight),
                                    )
                                }
                                if (hour > firstHour && hour % 4 == 0) {
                                    drawLine(
                                        line,
                                        Offset(area.left, y),
                                        Offset(area.right, y),
                                        1f,
                                        pathEffect = dash,
                                    )
                                }
                            }
                            for (column in 1 until 7) {
                                val x = area.left + column * columnWidth
                                drawLine(line, Offset(x, area.top), Offset(x, area.bottom), 1f)
                            }
                            for (box in boxes) {
                                val slotWidth = columnWidth / box.slots
                                val top =
                                    area.top + (box.startMinute - firstHour * 60) / 60f * hourHeight
                                val height =
                                    maxOf(
                                        (box.endMinute - box.startMinute) / 60f * hourHeight,
                                        2.dp.toPx(),
                                    )
                                drawEventBlock(
                                    pattern = box.event.pattern,
                                    color = eventColor(box.event.color),
                                    topLeft =
                                        Offset(
                                            area.left +
                                                box.column * columnWidth +
                                                box.slot * slotWidth +
                                                gap,
                                            top + gap / 2,
                                        ),
                                    size = Size(slotWidth - 2 * gap, height - gap),
                                    corner = CornerRadius(2.dp.toPx()),
                                    tint = 0.85f,
                                    bar = 0f,
                                    background = Color.Transparent,
                                )
                            }
                            // The hours of the dashed lines, small, at the left
                            for (hour in firstHour + 1 until lastHour) {
                                if (hour % 4 != 0) continue
                                painter.draw(
                                    scope = this,
                                    text = hour.toString(),
                                    x = area.left + 2.dp.toPx(),
                                    baseline =
                                        area.top + (hour - firstHour) * hourHeight - 1.dp.toPx(),
                                    clipRight = area.left + columnWidth,
                                    color = colors.onSurfaceVariant,
                                    sizePx = hourSize,
                                )
                            }
                        }
                    }
                }
        )
        // The current time: its own small layer, redrawn once a minute
        if (todayColumn >= 0 && nowMinute >= firstHour * 60 && nowMinute < lastHour * 60) {
            val now = colors.error
            Spacer(
                modifier =
                    Modifier.matchParentSize().drawWithCache {
                        val area = grid(size.width, size.height, 1.dp.toPx(), 18.dp.toPx())
                        val columnWidth = area.width / 7
                        val y =
                            area.top +
                                (nowMinute - firstHour * 60) / 60f *
                                    (area.height / (lastHour - firstHour))
                        val left = area.left + todayColumn * columnWidth
                        onDrawBehind {
                            drawLine(
                                now,
                                Offset(left, y),
                                Offset(left + columnWidth, y),
                                1.5.dp.toPx(),
                            )
                            drawCircle(now, 2.5.dp.toPx(), Offset(left, y))
                        }
                    }
            )
        }
    }
}
