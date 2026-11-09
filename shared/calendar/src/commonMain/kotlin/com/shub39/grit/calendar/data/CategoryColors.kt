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
package com.shub39.grit.calendar.data

import com.shub39.grit.caldav.davx5.Css3Color

/** Category colors are CSS color names, the format of the RFC 7986 COLOR property. */
object CategoryColors {
    /** Colors offered for new categories. */
    val palette =
        listOf(
            "royalblue",
            "crimson",
            "seagreen",
            "darkorange",
            "mediumpurple",
            "teal",
            "goldenrod",
            "hotpink",
            "sienna",
            "slategray",
            "deepskyblue",
            "olivedrab",
        )

    /** A stable color for a category that arrived without one. */
    fun defaultFor(name: String): String =
        palette[(name.lowercase().hashCode() and Int.MAX_VALUE) % palette.size]

    /** ARGB of a CSS color name or hex value, or null if it cannot be read. */
    fun argb(color: String?): Int? = color?.let(Css3Color::colorFromString)

    /** The CSS color name if [color] is a valid one, else null. */
    fun normalize(color: String?): String? = color?.let { Css3Color.fromString(it)?.name }
}
