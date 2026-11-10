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
package com.shub39.grit.calendar.autobackup

import androidx.compose.runtime.Composable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single

/** How often Grit writes a backup file by itself. */
enum class BackupFrequency(val days: Long) {
    Off(0),
    Daily(1),
    Weekly(7),
    Monthly(30),
}

data class AutoBackupState(
    val frequency: BackupFrequency = BackupFrequency.Off,
    /** How many automatic backup files stay in the folder. */
    val keep: Int = AutoBackupFiles.DEFAULT_KEEP,
    /** The name of the chosen folder, or null before the user chooses one. */
    val folderName: String? = null,
    /** UTC milliseconds of the last automatic backup that worked. */
    val lastBackup: Long? = null,
    val lastError: String? = null,
)

/**
 * Scheduled backups: on a schedule, Grit writes the same file as Export into a folder that the user
 * chose, and keeps only the newest automatic files there. Android only: the desktop app has no
 * background work.
 */
@Single
expect class AutoBackup {
    val supported: Boolean

    fun state(): Flow<AutoBackupState>

    fun setFrequency(frequency: BackupFrequency)

    fun setKeep(keep: Int)

    /** A folder that the system folder picker returned. */
    fun setFolder(uri: String)

    /** Writes a backup now and removes the oldest automatic ones. False if it failed. */
    suspend fun backUpNow(): Boolean
}

/** Returns a function that opens the system folder picker. */
@Composable expect fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit

/** Names of the automatic backup files, and which ones to remove. */
object AutoBackupFiles {
    const val DEFAULT_KEEP = 7
    const val MAX_KEEP = 99
    private const val PREFIX = "Grit-Auto-"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")
    private val pattern = Regex("""^Grit-Auto-\d{4}-\d{2}-\d{2}T\d{6}\.json$""")

    /** Names sort by time, so that the oldest files are easy to find. */
    fun fileName(now: LocalDateTime): String = PREFIX + stamp.format(now) + ".json"

    /**
     * The automatic files beyond the [keep] newest. Other files in the folder, such as manual
     * exports, are never in the list.
     */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.filter { pattern.matches(it) }.sortedDescending().drop(keep.coerceAtLeast(1))
}
