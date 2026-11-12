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

import java.net.URI

/**
 * The path of a calendar or task list on the server, without the host. One server can have two
 * addresses (for example after a move to a new domain): the same path is the same collection.
 */
object ServerPaths {
    /** The path of [url], with a final slash. */
    fun path(url: String): String =
        (runCatching { URI(url).rawPath }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url).trimEnd(
            '/'
        ) + "/"

    /** [url] at the address of [base]: the scheme, host and port of [base], the path of [url]. */
    fun rebase(url: String, base: String): String {
        val origin =
            base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        return origin + path(url)
    }
}
