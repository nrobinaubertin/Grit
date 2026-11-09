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

import java.io.File

/** Where the desktop app keeps calendar data. */
object DesktopPaths {
    /**
     * The folder from the `grit.calendar.dir` system property, or the Grit data folder used by
     * upstream for its settings. The property lets two desktop instances act as two users.
     */
    fun dataDir(): File {
        val override = System.getProperty("grit.calendar.dir")
        val dir =
            if (override != null) File(override)
            else {
                val os = System.getProperty("os.name").lowercase()
                val home = System.getProperty("user.home")
                when {
                    "win" in os -> File(System.getenv("APPDATA") ?: home, "Grit")
                    "mac" in os -> File(home, "Library/Application Support/Grit")
                    else -> File(home, ".local/share/Grit")
                }
            }
        dir.mkdirs()
        return dir
    }
}
