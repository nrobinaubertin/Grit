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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
    weeks: Map<LocalDate, WeekLanes>,
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

    // The previous and next pages are built a few frames after the first one
    var neighbours by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos {} }
        neighbours = 1
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
            beyondViewportPageCount = neighbours,
            key = { it },
        ) { page ->
            val pageMonth = origin.plus(page - PAGE_RANGE, DateTimeUnit.MONTH)
            MonthPage(
                month = pageMonth,
                selectedDate = selectedDate,
                today = today,
                firstDayOfWeek = firstDayOfWeek,
                weeks = weeks,
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
    weeks: Map<LocalDate, WeekLanes>,
    onSelect: (LocalDate) -> Unit,
) {
    val days = remember(month, firstDayOfWeek) { gridDays(month, firstDayOfWeek) }
    val rows = remember(days, weeks) { List(6) { weeks[days[it * 7]] ?: WeekLanes.Empty } }
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
        MonthDrawing(month, days, rows, today, Modifier.matchParentSize())
        SelectionLayer(days.indexOf(selectedDate), 7, Modifier.matchParentSize())
    }
}

/**
 * The fills of the day cells. Days of the shown month are lighter than the days of the months
 * around it, and today has a tint of the theme color.
 */
@Immutable
internal class CellColors(
    val day: Color,
    val otherMonth: Color,
    val weekend: Color,
    val today: Color,
    val selection: Color,
)

@Composable
internal fun rememberCellColors(): CellColors {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        // Dark themes: the shown days are lighter than the page. Light themes: the other days are
        // darker than the page. Either way, the other days are the darker ones.
        val dark = colors.background.luminance() < 0.5f
        CellColors(
            day = if (dark) colors.surfaceContainer else colors.surfaceContainerLowest,
            otherMonth = if (dark) colors.surfaceContainerLowest else colors.surfaceContainerHigh,
            weekend = if (dark) colors.surfaceContainerHigh else colors.surfaceContainer,
            today = colors.primary.copy(alpha = if (dark) 0.16f else 0.12f),
            selection = colors.primary,
        )
    }
}

/** The fill of one day cell at [topLeft]: [fill], with the tint of today over it if [isToday]. */
internal fun DrawScope.drawCell(
    cells: CellColors,
    fill: Color,
    isToday: Boolean,
    topLeft: Offset,
    size: Size,
) {
    val gap = 1.dp.toPx()
    val corner = CornerRadius(8.dp.toPx())
    val inner = Offset(topLeft.x + gap, topLeft.y + gap)
    val innerSize = Size(size.width - 2 * gap, size.height - 2 * gap)
    drawRoundRect(fill, inner, innerSize, corner)
    if (isToday) drawRoundRect(cells.today, inner, innerSize, corner)
}

/**
 * A frame around the selected day, over the cells and their events. It redraws alone when the
 * selection changes. [columns] is the number of cells in a row.
 */
@Composable
internal fun SelectionLayer(index: Int, columns: Int, modifier: Modifier, rows: Int = 6) {
    val color = rememberCellColors().selection
    Spacer(
        modifier =
            modifier.drawBehind {
                if (index < 0) return@drawBehind
                val cellWidth = size.width / columns
                val cellHeight = size.height / rows
                val stroke = 2.dp.toPx()
                val inset = 1.dp.toPx() + stroke / 2
                drawRoundRect(
                    color = color,
                    topLeft =
                        Offset(
                            (index % columns) * cellWidth + inset,
                            (index / columns) * cellHeight + inset,
                        ),
                    size = Size(cellWidth - 2 * inset, cellHeight - 2 * inset),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
    )
}

/**
 * Day numbers, and one line per event with the start of its title. Multi-day events are one bar
 * over their days.
 */
@Composable
private fun MonthDrawing(
    month: YearMonth,
    days: List<LocalDate>,
    rows: List<WeekLanes>,
    today: LocalDate,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val cells = rememberCellColors()
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
                val lineStep = lineHeight + 1.dp.toPx()
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
                        val left = (index % 7) * cellWidth
                        val top = (index / 7) * cellHeight
                        val center = left + cellWidth / 2
                        val isToday = date == today
                        drawCell(
                            cells = cells,
                            fill = if (date.month == month.month) cells.day else cells.otherMonth,
                            isToday = isToday,
                            topLeft = Offset(left, top),
                            size = Size(cellWidth, cellHeight),
                        )
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
                                    date.month == month.month -> colors.onSurface
                                    else -> faded
                                },
                            sizePx = numberSize,
                            bold = isToday,
                            centered = true,
                        )
                    }
                    for ((row, lanes) in rows.withIndex()) {
                        val top = row * cellHeight + firstLine
                        for (segment in lanes.segments) {
                            val event = segment.event
                            val color = eventColor(event.color)
                            val spanning =
                                event.allDay ||
                                    segment.startColumn != segment.endColumn ||
                                    segment.continuesBefore ||
                                    segment.continuesAfter
                            val left =
                                segment.startColumn * cellWidth +
                                    if (segment.continuesBefore) 0f else inset
                            val right =
                                (segment.endColumn + 1) * cellWidth -
                                    if (segment.continuesAfter) 0f else inset
                            val y = top + segment.position * lineStep
                            val textX = left + if (spanning) inset * 2 else bar + inset
                            // The pattern tells the calendar, the color the category
                            drawEventBlock(
                                pattern = event.pattern,
                                color = color,
                                topLeft = Offset(left, y),
                                size = Size(right - left, lineHeight),
                                corner = corner,
                                tint = if (spanning) 0.55f else 0.18f,
                                bar = if (spanning) 0f else bar,
                                // The cell shows through the patterns without a fill
                                background = Color.Transparent,
                                text =
                                    painter.textArea(
                                        pattern = event.pattern,
                                        text = event.title,
                                        sizePx = lineSize,
                                        x = textX,
                                        clipRight = right - inset,
                                        // Past the bottom edge: the text fills the line
                                        bottom = y + lineHeight + 1f,
                                        margin = inset,
                                    ),
                            )
                            painter.draw(
                                scope = this,
                                text = event.title,
                                x = textX,
                                baseline = y + lineHeight * 0.75f,
                                clipRight = right - inset,
                                color = colors.onSurface,
                                sizePx = lineSize,
                            )
                        }
                        for ((column, count) in lanes.hidden.withIndex()) {
                            if (count == 0) continue
                            painter.draw(
                                scope = this,
                                text = "+$count",
                                x = column * cellWidth + inset * 2,
                                baseline = top + (MONTH_LANES - 1) * lineStep + lineHeight * 0.75f,
                                clipRight = (column + 1) * cellWidth,
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
