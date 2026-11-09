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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.shared.ui.habit.daysStartingFrom
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Months on each side of the current month that the pager can reach. */
private const val PAGE_RANGE = 20 * 12

private val CELL_HEIGHT = 88.dp
private const val MAX_EVENT_LINES = 4

/**
 * Swipeable month grid. Each month is one drawing: no composable per day, and labels are drawn on
 * the platform canvas. This keeps the grid cheap enough for slow phones: composing a page creates a
 * few nodes, and selecting a day only redraws the small selection layer.
 */
@Composable
internal fun MonthPager(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    eventsByDay: Map<LocalDate, List<DayEvent>>,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Page indexes count months from a fixed origin
    val origin = remember { today.let { YearMonth(it.year, it.month) } }
    fun pageOf(target: YearMonth) = PAGE_RANGE + monthsBetween(origin, target)
    val pagerState =
        rememberPagerState(initialPage = pageOf(month).coerceIn(0, 2 * PAGE_RANGE)) {
            2 * PAGE_RANGE + 1
        }
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { currentOnMonthChange(origin.plus(it - PAGE_RANGE, DateTimeUnit.MONTH)) }
    }
    LaunchedEffect(month) {
        val target = pageOf(month).coerceIn(0, 2 * PAGE_RANGE)
        if (pagerState.settledPage != target) {
            if (abs(pagerState.settledPage - target) <= 2) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            for (day in daysStartingFrom(firstDayOfWeek)) {
                Text(
                    text = Formatting.weekday(day),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            // Keep the next and previous months ready, so that a swipe shows them at once
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            val pageMonth = origin.plus(page - PAGE_RANGE, DateTimeUnit.MONTH)
            MonthPage(
                month = pageMonth,
                selectedDate = selectedDate,
                today = today,
                firstDayOfWeek = firstDayOfWeek,
                eventsByDay = eventsByDay,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDayOfWeek: DayOfWeek,
    eventsByDay: Map<LocalDate, List<DayEvent>>,
    onSelect: (LocalDate) -> Unit,
) {
    val days = remember(month, firstDayOfWeek) { gridDays(month, firstDayOfWeek) }
    val pageEvents = remember(days, eventsByDay) { days.map { eventsByDay[it].orEmpty() } }
    val currentOnSelect by rememberUpdatedState(onSelect)

    Box(
        modifier =
            Modifier.fillMaxWidth().height(CELL_HEIGHT * 6).padding(horizontal = 4.dp).pointerInput(
                days
            ) {
                detectTapGestures { offset ->
                    val column = (offset.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    val row = (offset.y / (size.height / 6f)).toInt().coerceIn(0, 5)
                    currentOnSelect(days[row * 7 + column])
                }
            }
    ) {
        SelectionLayer(days.indexOf(selectedDate), Modifier.matchParentSize())
        MonthDrawing(month, days, pageEvents, today, Modifier.matchParentSize())
    }
}

/** The highlight behind the selected day. It redraws alone when the selection changes. */
@Composable
private fun SelectionLayer(index: Int, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Spacer(
        modifier =
            modifier.drawBehind {
                if (index < 0) return@drawBehind
                val cellWidth = size.width / 7
                val cellHeight = size.height / 6
                drawRoundRect(
                    color = color,
                    topLeft =
                        Offset(
                            (index % 7) * cellWidth + 1.dp.toPx(),
                            (index / 7) * cellHeight + 1.dp.toPx(),
                        ),
                    size = Size(cellWidth - 2.dp.toPx(), cellHeight - 2.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
    )
}

/** Day numbers and one line per event, with the start of its title. */
@Composable
private fun MonthDrawing(
    month: YearMonth,
    days: List<LocalDate>,
    events: List<List<DayEvent>>,
    today: LocalDate,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val painter = remember { GridTextPainter() }
    Spacer(
        modifier =
            // Own layer: the recorded drawing is reused until the month or its events change
            modifier.graphicsLayer().drawWithCache {
                val cellWidth = size.width / 7
                val cellHeight = size.height / 6
                val numberSize = 12.sp.toPx()
                val lineSize = 10.sp.toPx()
                val lineHeight = 14.dp.toPx()
                val lineGap = 1.dp.toPx()
                val todayRadius = 11.dp.toPx()
                val numberCenter = 2.dp.toPx() + todayRadius
                val firstLine = numberCenter + todayRadius + 2.dp.toPx()
                val inset = 2.dp.toPx()
                val bar = 2.dp.toPx()
                val corner = CornerRadius(3.dp.toPx())
                val faded = colors.onSurface.copy(alpha = 0.38f)
                onDrawBehind {
                    for (index in days.indices) {
                        val date = days[index]
                        val inMonth = date.month == month.month
                        val left = (index % 7) * cellWidth
                        val top = (index / 7) * cellHeight
                        val center = left + cellWidth / 2

                        val isToday = date == today
                        if (isToday) {
                            drawCircle(
                                colors.primary,
                                todayRadius,
                                Offset(center, top + numberCenter),
                            )
                        }
                        painter.draw(
                            scope = this,
                            text = date.day.toString(),
                            x = center,
                            baseline = top + numberCenter + numberSize * 0.36f,
                            clipRight = left + cellWidth,
                            color =
                                when {
                                    isToday -> colors.onPrimary
                                    inMonth -> colors.onSurface
                                    else -> faded
                                },
                            sizePx = numberSize,
                            bold = isToday,
                            centered = true,
                        )

                        val dayEvents = events[index]
                        if (dayEvents.isEmpty()) continue
                        val shown =
                            if (dayEvents.size > MAX_EVENT_LINES) MAX_EVENT_LINES - 1
                            else dayEvents.size
                        val right = left + cellWidth - inset
                        var y = top + firstLine
                        for (i in 0 until shown) {
                            val event = dayEvents[i]
                            val base = event.color?.let { Color(it) } ?: colors.primary
                            val accent = if (inMonth) base else base.copy(alpha = 0.5f)
                            drawRoundRect(
                                color = accent.copy(alpha = if (event.allDay) 0.45f else 0.18f),
                                topLeft = Offset(left + inset, y),
                                size = Size(right - left - inset, lineHeight),
                                cornerRadius = corner,
                            )
                            drawRect(accent, Offset(left + inset, y), Size(bar, lineHeight))
                            painter.draw(
                                scope = this,
                                text = event.title,
                                x = left + inset + bar + inset,
                                baseline = y + lineHeight * 0.75f,
                                clipRight = right,
                                color = if (inMonth) colors.onSurface else faded,
                                sizePx = lineSize,
                            )
                            y += lineHeight + lineGap
                        }
                        if (dayEvents.size > shown) {
                            painter.draw(
                                scope = this,
                                text = "+${dayEvents.size - shown}",
                                x = left + inset * 2,
                                baseline = y + lineHeight * 0.75f,
                                clipRight = right,
                                color = colors.onSurfaceVariant,
                                sizePx = lineSize,
                            )
                        }
                    }
                }
            }
    )
}

/** The 42 dates of a six-week grid that contains [month]. */
internal fun gridDays(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = LocalDate(month.year, month.month, 1)
    val offset = (first.dayOfWeek.ordinal - firstDayOfWeek.ordinal + 7) % 7
    val start = first.minus(DatePeriod(days = offset))
    return List(42) { start.plus(DatePeriod(days = it)) }
}

private fun monthsBetween(from: YearMonth, to: YearMonth): Int =
    (to.year - from.year) * 12 + (to.month.ordinal - from.month.ordinal)
