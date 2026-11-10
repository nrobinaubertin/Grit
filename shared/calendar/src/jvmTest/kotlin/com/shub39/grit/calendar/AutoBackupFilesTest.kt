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
package com.shub39.grit.calendar

import com.shub39.grit.calendar.autobackup.AutoBackupFiles
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoBackupFilesTest {
    @Test
    fun `names sort by time`() {
        assertEquals(
            "Grit-Auto-2026-11-10T180502.json",
            AutoBackupFiles.fileName(LocalDateTime.of(2026, 11, 10, 18, 5, 2)),
        )
    }

    @Test
    fun `only the oldest automatic files go, other files stay`() {
        val names =
            listOf(
                "Grit-Auto-2026-11-08T020000.json",
                "Grit-Export-2026-11-01T120000.json",
                "Grit-Auto-2026-11-10T020000.json",
                "notes.txt",
                "Grit-Auto-2026-11-09T020000.json",
                "Grit-Auto-2026-11-07T020000.json",
                "Grit-Auto-2026-11-06T020000 (1).json",
            )
        assertEquals(
            listOf("Grit-Auto-2026-11-08T020000.json", "Grit-Auto-2026-11-07T020000.json"),
            AutoBackupFiles.toDelete(names, keep = 2),
        )
        assertEquals(emptyList(), AutoBackupFiles.toDelete(names, keep = 10))
        // At least one file always stays
        assertEquals(3, AutoBackupFiles.toDelete(names, keep = 0).size)
    }
}
