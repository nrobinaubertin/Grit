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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.data.CalendarPattern

/** The alpha of the pattern lines behind event text: the lines show, the text stays readable. */
private const val TEXT_LINE_ALPHA = 0.4f

/** The color of an event without a category color: a neutral grey, in light and dark themes. */
internal val NoCategoryColor = Color(0xFF808080)

/** The color to draw an event with: its category color, or [NoCategoryColor]. */
internal fun eventColor(argb: Int?): Color = argb?.let { Color(it) } ?: NoCategoryColor

/**
 * Whether [this] pattern draws lines over the whole block, so that its text needs fainter lines.
 */
internal val CalendarPattern.crossesText: Boolean
    get() = this == CalendarPattern.Hatched || this == CalendarPattern.Crossed

/**
 * The area of an event block where the lines of [pattern] are fainter, so that the text stays
 * readable: from the left and top edges of the block to [margin] past the end of [text] drawn from
 * [x], and down to [bottom]. Text cut at [clipRight] covers the block up to its right edge. The
 * area starts at 0, past the left and top edges, so that no partly covered row or column of pixels
 * along an edge shows lines of both strengths. Empty when the pattern draws no lines over the text.
 */
internal fun GridTextPainter.textArea(
    pattern: CalendarPattern,
    text: String,
    sizePx: Float,
    x: Float,
    clipRight: Float,
    bottom: Float,
    margin: Float,
    bold: Boolean = false,
): Rect {
    if (!pattern.crossesText || text.isEmpty()) return Rect.Zero
    val end = x + width(text, sizePx, bold)
    // One more pixel past the right edge of the block when the text reaches it
    val right = if (end >= clipRight) clipRight + margin + 1f else end + margin
    return Rect(0f, 0f, right, bottom)
}

/**
 * Draws an event block in [color] with [pattern]. [tint] is the alpha of the solid fill; [bar] the
 * width of the side bar of the filled patterns, or 0 for none; [background] covers the grid under
 * the patterns that have no fill. The lines of the hatched patterns are fainter in [text], where
 * the text goes, so that the pattern shows behind the text and the text stays readable.
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
    text: Rect = Rect.Zero,
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
            val width = 1.2.dp.toPx()
            // Lines at 45 degrees, at the same places in every block so that blocks line up
            fun lines(line: Color) {
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
            clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
                clipRect(text.left, text.top, text.right, text.bottom, ClipOp.Difference) {
                    lines(color.copy(alpha = 0.7f))
                }
                if (!text.isEmpty) {
                    clipRect(text.left, text.top, text.right, text.bottom) {
                        lines(color.copy(alpha = TEXT_LINE_ALPHA))
                    }
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
