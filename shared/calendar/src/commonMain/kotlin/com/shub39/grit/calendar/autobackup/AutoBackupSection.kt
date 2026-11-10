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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.calendar.ui.LightSelector
import com.shub39.grit.shared.ui.navigation.BackupPageContributor
import grit.shared.calendar.generated.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.core.annotation.Single

/** Puts the scheduled backups on the Backup page of the settings. */
@Single(binds = [BackupPageContributor::class])
class AutoBackupPageContributor(private val backup: AutoBackup) : BackupPageContributor {
    @Composable
    override fun Section() {
        if (backup.supported) AutoBackupSection(backup)
    }
}

@Composable
private fun AutoBackupSection(backup: AutoBackup) {
    val flow = remember(backup) { backup.state() }
    val state by flow.collectAsState(AutoBackupState())
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val pickFolder = rememberFolderPicker(backup::setFolder)
    val colors = MaterialTheme.colorScheme

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainer)
                .padding(16.dp),
    ) {
        Text(stringResource(Res.string.auto_backup), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.auto_backup_body),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.auto_backup_folder))
                Text(
                    state.folderName ?: stringResource(Res.string.auto_backup_no_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(onClick = pickFolder) {
                Text(stringResource(Res.string.auto_backup_choose_folder))
            }
        }

        LightSelector(
            options = BackupFrequency.entries,
            selected = state.frequency,
            label = {
                stringResource(
                    when (it) {
                        BackupFrequency.Off -> Res.string.auto_backup_off
                        BackupFrequency.Daily -> Res.string.auto_backup_daily
                        BackupFrequency.Weekly -> Res.string.auto_backup_weekly
                        BackupFrequency.Monthly -> Res.string.auto_backup_monthly
                    }
                )
            },
            onSelect = backup::setFrequency,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.auto_backup_keep), modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { backup.setKeep(state.keep - 1) },
                enabled = state.keep > 1,
            ) {
                Text("−")
            }
            Text(
                state.keep.toString(),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            FilledTonalIconButton(
                onClick = { backup.setKeep(state.keep + 1) },
                enabled = state.keep < AutoBackupFiles.MAX_KEEP,
            ) {
                Text("+")
            }
        }

        Text(
            state.lastBackup?.let {
                stringResource(Res.string.auto_backup_last, Formatting.dateTime(it))
            } ?: stringResource(Res.string.auto_backup_never),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        state.lastError?.let {
            Text(
                stringResource(Res.string.auto_backup_error, it),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }

        Button(
            onClick = {
                running = true
                scope.launch {
                    backup.backUpNow()
                    running = false
                }
            },
            enabled = state.folderName != null && !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp))
            } else {
                Text(stringResource(Res.string.auto_backup_now))
            }
        }
    }
}
