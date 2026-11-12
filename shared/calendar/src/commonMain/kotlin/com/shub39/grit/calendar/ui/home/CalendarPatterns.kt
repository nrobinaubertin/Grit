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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.data.CalendarPattern

/** The color of an event without a category color: a neutral grey, in light and dark themes. */
internal val NoCategoryColor = Color(0xFF808080)

/** The color to draw an event with: its category color, or [NoCategoryColor]. */
internal fun eventColor(argb: Int?): Color = argb?.let { Color(it) } ?: NoCategoryColor

/**
 * Draws an event block in [color] with [pattern]. [tint] is the alpha of the solid fill; [bar] the
 * width of the side bar of the filled patterns, or 0 for none; [background] covers the grid under
 * the patterns that have no fill.
 */
internal fun DrawScope.drawEventBlock(
    pattern: CalendarPattern,
    color: Color,
    topLeft: Offset,
    size: Size,
    corner: CornerRadius,
    tint: Float,
    bar: Float,
    background: Color,
) {
    val stroke = 1.5.dp.toPx()
    when (pattern) {
        CalendarPattern.Solid -> {
            drawRoundRect(color.copy(alpha = tint), topLeft, size, corner)
            if (bar > 0f) drawRect(color, topLeft, Size(bar, size.height))
        }
        CalendarPattern.Hatched,
        CalendarPattern.Crossed -> {
            drawRoundRect(color.copy(alpha = tint * 0.35f), topLeft, size, corner)
            val step = 5.dp.toPx()
            val line = color.copy(alpha = 0.7f)
            val width = 1.2.dp.toPx()
            clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
                // Lines at 45 degrees, at the same places in every block so that blocks line up
                val reach = size.width + size.height
                var d = -(topLeft.x + topLeft.y) % step - size.height
                while (d < reach) {
                    val x = topLeft.x + d
                    drawLine(
                        line,
                        Offset(x, topLeft.y + size.height),
                        Offset(x + size.height, topLeft.y),
                        width,
                    )
                    if (pattern == CalendarPattern.Crossed) {
                        drawLine(
                            line,
                            Offset(x, topLeft.y),
                            Offset(x + size.height, topLeft.y + size.height),
                            width,
                        )
                    }
                    d += step
                }
            }
            if (bar > 0f) drawRect(color, topLeft, Size(bar, size.height))
        }
        CalendarPattern.Outline,
        CalendarPattern.Dashed,
        CalendarPattern.Dotted -> {
            drawRoundRect(background, topLeft, size, corner)
            val effect =
                when (pattern) {
                    CalendarPattern.Dashed ->
                        PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.5.dp.toPx()))
                    CalendarPattern.Dotted ->
                        PathEffect.dashPathEffect(floatArrayOf(0.1f, 3.dp.toPx()))
                    else -> null
                }
            drawRoundRect(
                color,
                Offset(topLeft.x + stroke / 2, topLeft.y + stroke / 2),
                Size(size.width - stroke, size.height - stroke),
                corner,
                style =
                    Stroke(
                        width = if (pattern == CalendarPattern.Dotted) stroke * 1.4f else stroke,
                        cap =
                            if (pattern == CalendarPattern.Dotted) StrokeCap.Round
                            else StrokeCap.Butt,
                        pathEffect = effect,
                    ),
            )
        }
    }
}

/** A small sample of a pattern, for legends and lists. */
@Composable
internal fun PatternSwatch(
    pattern: CalendarPattern,
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 18.dp,
    height: Dp = 12.dp,
) {
    val background = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier.size(width, height)) {
        drawEventBlock(
            pattern,
            color,
            Offset.Zero,
            size,
            CornerRadius(3.dp.toPx()),
            tint = 0.45f,
            bar = 3.dp.toPx(),
            background = background,
        )
    }
}
