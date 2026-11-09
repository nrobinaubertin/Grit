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
package com.shub39.grit.calendar.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shub39.grit.calendar.data.DesktopPaths
import java.io.File
import org.koin.core.annotation.Single

/** Desktop: a file database, so that unsent edits survive a restart. */
@Single
actual class CalendarDbFactory {
    actual fun create(): RoomDatabase.Builder<CalendarDatabase> {
        val file = File(DesktopPaths.dataDir(), "${CalendarDatabase.DB_NAME}.db")
        return Room.databaseBuilder<CalendarDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
    }
}
