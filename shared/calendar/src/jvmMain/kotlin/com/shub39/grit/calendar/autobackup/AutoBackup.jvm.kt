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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Single

/** The desktop app has no background work, so it has no scheduled backups. */
@Single
actual class AutoBackup {
    actual val supported: Boolean = false

    actual fun state(): Flow<AutoBackupState> = flowOf(AutoBackupState())

    actual fun setFrequency(frequency: BackupFrequency) = Unit

    actual fun setKeep(keep: Int) = Unit

    actual fun setFolder(uri: String) = Unit

    actual suspend fun backUpNow(): Boolean = false
}

@Composable actual fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = {}
