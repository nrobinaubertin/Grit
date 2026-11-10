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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shub39.grit.calendar.ui.Formatting
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Weeks on each side of the current week that the pager can reach. */
private const val PAGE_RANGE = 52 * 10

private val GUTTER = 44.dp
private val HEADER_HEIGHT = 48.dp

/**
 * Swipeable week view: a header with the days, a band with all-day and multi-day events, and an
 * hour grid. The 24 hours fit the screen height, so the whole week shows without scrolling. Every
 * part is one drawing, like the month grid, so that slow phones keep up.
 */
@Composable
internal fun WeekPager(
    week: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    weeks: Map<LocalDate, WeekData>,
    is24Hr: Boolean,
    canCreate: Boolean,
    onWeekChange: (LocalDate) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
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
            selectedDate = selectedDate,
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

@Composable
private fun WeekPage(
    start: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    data: WeekData,
    is24Hr: Boolean,
    canCreate: Boolean,
    nowMinute: Int,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
) {
    val days = remember(start) { List(7) { start.plus(DatePeriod(days = it)) } }
    Column(modifier = Modifier.fillMaxSize()) {
        WeekHeader(days, selectedDate, today, onSelect)
        WeekBand(days, data.band, onSelect, onOpenEvent)
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            HourGrid(
                days = days,
                boxes = data.boxes,
                todayColumn = days.indexOf(today),
                nowMinute = nowMinute,
                is24Hr = is24Hr,
                canCreate = canCreate,
                onSelect = onSelect,
                onOpenEvent = onOpenEvent,
                onCreateAt = onCreateAt,
            )
        }
    }
}

@Composable
private fun WeekHeader(
    days: List<LocalDate>,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val painter = remember { GridTextPainter() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    Spacer(
        modifier =
            Modifier.fillMaxWidth()
                .height(HEADER_HEIGHT)
                .pointerInput(days) {
                    detectTapGestures { offset ->
                        val gutter = GUTTER.toPx()
                        if (offset.x < gutter) return@detectTapGestures
                        val column = ((offset.x - gutter) / ((size.width - gutter) / 7f)).toInt()
                        currentOnSelect(days[column.coerceIn(0, 6)])
                    }
                }
                .drawWithCache {
                    val gutter = GUTTER.toPx()
                    val columnWidth = (size.width - gutter) / 7
                    val nameSize = 11.sp.toPx()
                    val numberSize = 15.sp.toPx()
                    val radius = 14.dp.toPx()
                    val numberCenter = size.height - radius - 2.dp.toPx()
                    onDrawBehind {
                        for ((index, day) in days.withIndex()) {
                            val center = gutter + columnWidth * (index + 0.5f)
                            val isToday = day == today
                            val selected = day == selectedDate
                            painter.draw(
                                scope = this,
                                text = Formatting.weekday(day.dayOfWeek),
                                x = center,
                                baseline = nameSize * 1.1f,
                                clipRight = center + columnWidth / 2,
                                color = if (isToday) colors.primary else colors.onSurfaceVariant,
                                sizePx = nameSize,
                                centered = true,
                            )
                            if (isToday || selected) {
                                drawCircle(
                                    if (isToday) colors.primary else colors.surfaceContainerHighest,
                                    radius,
                                    Offset(center, numberCenter),
                                )
                            }
                            painter.draw(
                                scope = this,
                                text = day.day.toString(),
                                x = center,
                                baseline = numberCenter + numberSize * 0.36f,
                                clipRight = center + columnWidth / 2,
                                color = if (isToday) colors.onPrimary else colors.onSurface,
                                sizePx = numberSize,
                                bold = isToday || selected,
                                centered = true,
                            )
                        }
                    }
                }
    )
}

/** All-day and multi-day events above the hour grid, one bar over their days. */
@Composable
private fun WeekBand(
    days: List<LocalDate>,
    lanes: WeekLanes,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val painter = remember { GridTextPainter() }
    val used =
        if (lanes.hidden.any { it > 0 }) WEEK_BAND_LANES
        else (lanes.segments.maxOfOrNull { it.lane } ?: -1) + 1
    val lineHeight = 18.dp
    val lineGap = 2.dp
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpen by rememberUpdatedState(onOpenEvent)
    Spacer(
        modifier =
            Modifier.fillMaxWidth()
                .height(if (used == 0) 4.dp else (lineHeight + lineGap) * used + 4.dp)
                .pointerInput(days, lanes) {
                    detectTapGestures { offset ->
                        val gutter = GUTTER.toPx()
                        if (offset.x < gutter) return@detectTapGestures
                        val column =
                            ((offset.x - gutter) / ((size.width - gutter) / 7f))
                                .toInt()
                                .coerceIn(0, 6)
                        val lane =
                            ((offset.y - 2.dp.toPx()) / (lineHeight + lineGap).toPx()).toInt()
                        val segment =
                            lanes.segments.firstOrNull {
                                it.lane == lane && column in it.startColumn..it.endColumn
                            }
                        if (segment != null) currentOnOpen(segment.event)
                        else currentOnSelect(days[column])
                    }
                }
                .drawWithCache {
                    val gutter = GUTTER.toPx()
                    val columnWidth = (size.width - gutter) / 7
                    val height = lineHeight.toPx()
                    val step = height + lineGap.toPx()
                    val inset = 2.dp.toPx()
                    val textSize = 11.sp.toPx()
                    val corner = CornerRadius(4.dp.toPx())
                    onDrawBehind {
                        for (segment in lanes.segments) {
                            val color = segment.event.color?.let { Color(it) } ?: colors.primary
                            val left =
                                gutter +
                                    segment.startColumn * columnWidth +
                                    if (segment.continuesBefore) 0f else inset
                            val right =
                                gutter + (segment.endColumn + 1) * columnWidth -
                                    if (segment.continuesAfter) 0f else inset
                            val y = 2.dp.toPx() + segment.lane * step
                            drawRoundRect(
                                color.copy(alpha = 0.55f),
                                Offset(left, y),
                                Size(right - left, height),
                                corner,
                            )
                            painter.draw(
                                scope = this,
                                text = segment.event.title,
                                x = left + inset * 2,
                                baseline = y + height * 0.72f,
                                clipRight = right - inset,
                                color = colors.onSurface,
                                sizePx = textSize,
                            )
                        }
                        for ((column, count) in lanes.hidden.withIndex()) {
                            if (count == 0) continue
                            painter.draw(
                                scope = this,
                                text = "+$count",
                                x = gutter + column * columnWidth + inset * 2,
                                baseline =
                                    2.dp.toPx() + (WEEK_BAND_LANES - 1) * step + height * 0.72f,
                                clipRight = gutter + (column + 1) * columnWidth,
                                color = colors.onSurfaceVariant,
                                sizePx = textSize,
                            )
                        }
                    }
                }
    )
}

@Composable
private fun HourGrid(
    days: List<LocalDate>,
    boxes: List<TimedBox>,
    todayColumn: Int,
    nowMinute: Int,
    is24Hr: Boolean,
    canCreate: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (DayEvent) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val painter = remember { GridTextPainter() }
    val hourLabels = remember(is24Hr) { List(24) { Formatting.time(LocalTime(it, 0), is24Hr) } }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpen by rememberUpdatedState(onOpenEvent)
    val currentOnCreate by rememberUpdatedState(onCreateAt)
    val currentCanCreate by rememberUpdatedState(canCreate)
    Box(
        modifier =
            Modifier.fillMaxSize().pointerInput(days, boxes) {
                detectTapGestures { offset ->
                    val gutter = GUTTER.toPx()
                    if (offset.x < gutter) return@detectTapGestures
                    val columnWidth = (size.width - gutter) / 7f
                    val hourHeight = size.height / 24f
                    val hit =
                        boxes.lastOrNull { box ->
                            val left =
                                gutter +
                                    box.column * columnWidth +
                                    box.slot * columnWidth / box.slots
                            val top = box.startMinute / 60f * hourHeight
                            val bottom = box.endMinute / 60f * hourHeight
                            offset.x >= left &&
                                offset.x < left + columnWidth / box.slots &&
                                offset.y >= top &&
                                offset.y < bottom
                        }
                    val column = ((offset.x - gutter) / columnWidth).toInt().coerceIn(0, 6)
                    when {
                        hit != null -> currentOnOpen(hit.event)
                        currentCanCreate ->
                            currentOnCreate(
                                days[column],
                                (offset.y / hourHeight).toInt().coerceIn(0, 23),
                            )
                        else -> currentOnSelect(days[column])
                    }
                }
            }
    ) {
        // Hours and events: redrawn only when the week's events change
        Spacer(
            modifier =
                Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                    val gutter = GUTTER.toPx()
                    val columnWidth = (size.width - gutter) / 7
                    val hourHeight = size.height / 24f
                    val labelSize = 10.sp.toPx()
                    // On a short screen, label every second or third hour so that labels never
                    // touch
                    val labelStep =
                        listOf(1, 2, 3, 4, 6).first {
                            it * hourHeight >= labelSize * 1.5f || it == 6
                        }
                    val minTitleSize = 7.sp.toPx()
                    val titleSize = 11.sp.toPx()
                    val timeSize = 10.sp.toPx()
                    val inset = 1.dp.toPx()
                    val bar = 3.dp.toPx()
                    val corner = CornerRadius(4.dp.toPx())
                    val line = colors.outlineVariant.copy(alpha = 0.5f)
                    onDrawBehind {
                        for (hour in 0 until 24) {
                            val y = hour * hourHeight
                            drawLine(line, Offset(gutter, y), Offset(size.width, y), 1f)
                            if (hour > 0 && hour % labelStep == 0) {
                                painter.draw(
                                    scope = this,
                                    text = hourLabels[hour],
                                    x = gutter / 2,
                                    baseline = y + labelSize * 0.35f,
                                    clipRight = gutter,
                                    color = colors.onSurfaceVariant,
                                    sizePx = labelSize,
                                    centered = true,
                                )
                            }
                        }
                        for (column in 1 until 7) {
                            val x = gutter + column * columnWidth
                            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                        for (box in boxes) {
                            val event = box.event
                            val color = event.color?.let { Color(it) } ?: colors.primary
                            val width = columnWidth / box.slots
                            val left = gutter + box.column * columnWidth + box.slot * width + inset
                            val top = box.startMinute / 60f * hourHeight + inset
                            val height =
                                (box.endMinute - box.startMinute) / 60f * hourHeight - 2 * inset
                            drawRoundRect(
                                color.copy(alpha = 0.35f),
                                Offset(left, top),
                                Size(width - 2 * inset, height),
                                corner,
                            )
                            drawRect(color, Offset(left, top), Size(bar, height))
                            val right = left + width - 2 * inset
                            // Short events get a smaller title, the shortest only their color
                            val eventTitleSize = minOf(titleSize, height / 1.25f)
                            if (eventTitleSize >= minTitleSize) {
                                painter.draw(
                                    scope = this,
                                    text = event.title,
                                    x = left + bar + 2 * inset,
                                    baseline = top + eventTitleSize * 1.05f,
                                    clipRight = right,
                                    color = colors.onSurface,
                                    sizePx = eventTitleSize,
                                    bold = true,
                                )
                            }
                            if (height > titleSize * 2.6f) {
                                painter.draw(
                                    scope = this,
                                    text =
                                        Formatting.time(
                                            event.begin,
                                            java.time.ZoneId.systemDefault(),
                                            is24Hr,
                                        ),
                                    x = left + bar + 2 * inset,
                                    baseline = top + titleSize * 1.1f + timeSize * 1.25f,
                                    clipRight = right,
                                    color = colors.onSurfaceVariant,
                                    sizePx = timeSize,
                                )
                            }
                        }
                    }
                }
        )
        // The current time: its own small layer, redrawn once a minute
        if (todayColumn >= 0) {
            val now = colors.error
            Spacer(
                modifier =
                    Modifier.fillMaxSize().drawWithCache {
                        val gutter = GUTTER.toPx()
                        val columnWidth = (size.width - gutter) / 7
                        val y = nowMinute / 60f * (size.height / 24f)
                        val left = gutter + todayColumn * columnWidth
                        onDrawBehind {
                            drawLine(
                                now,
                                Offset(left, y),
                                Offset(left + columnWidth, y),
                                2.dp.toPx(),
                            )
                            drawCircle(now, 4.dp.toPx(), Offset(left, y))
                        }
                    }
            )
        }
    }
}
