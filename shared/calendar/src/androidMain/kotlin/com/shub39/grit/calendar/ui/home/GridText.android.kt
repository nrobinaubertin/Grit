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

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

internal actual class GridTextPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    actual fun draw(
        scope: DrawScope,
        text: String,
        x: Float,
        baseline: Float,
        clipRight: Float,
        color: Color,
        sizePx: Float,
        bold: Boolean,
        centered: Boolean,
    ) {
        paint.textSize = sizePx
        paint.color = color.toArgb()
        paint.typeface = if (bold) this.bold else Typeface.DEFAULT
        paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val canvas = scope.drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.clipRect(
            if (centered) 0f else x,
            baseline - sizePx * 1.2f,
            clipRight,
            baseline + sizePx * 0.4f,
        )
        canvas.drawText(text, x, baseline, paint)
        canvas.restore()
    }
}
