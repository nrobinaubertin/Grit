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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface

internal actual class GridTextPainter {
    private val paint = Paint().apply { isAntiAlias = true }
    private val regular = typeface(FontStyle.NORMAL)
    private val bold = typeface(FontStyle.BOLD)

    private fun typeface(style: FontStyle): Typeface? =
        listOf("sans-serif", "DejaVu Sans", "Arial", "Helvetica").firstNotNullOfOrNull {
            FontMgr.default.matchFamilyStyle(it, style)
        } ?: FontMgr.default.legacyMakeTypeface("", style)

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
        val font = Font(if (bold) this.bold else regular, sizePx)
        paint.color = color.toArgb()
        val left = if (centered) x - font.measureTextWidth(text) / 2 else x
        val canvas = scope.drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.clipRect(
            Rect.makeLTRB(
                if (centered) 0f else x,
                baseline - sizePx * 1.2f,
                clipRight,
                baseline + sizePx * 0.4f,
            )
        )
        canvas.drawString(text, left, baseline, font, paint)
        canvas.restore()
    }
}
