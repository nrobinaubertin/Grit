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
package com.shub39.grit.calendar.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.LightTopBar
import grit.shared.calendar.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * The CalDAV server of Grit, in the global settings: the account, and the task lists. The calendars
 * of the server are on the calendar settings screen.
 */
@Composable
fun SyncScreen(state: AccountState, viewModel: AccountViewModel, onBack: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    var stopTaskList by remember { mutableStateOf<TaskListUi?>(null) }
    var startCategory by remember { mutableStateOf<TaskCategoryUi?>(null) }
    val syncToggle by viewModel.syncToggle.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            LightTopBar(
                title = stringResource(Res.string.sync_server),
                navigation = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(vectorResource(Res.drawable.nav_arrow_back), contentDescription = null)
                    }
                },
            )
            if (!state.loaded) return@Column
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column {
                        SectionTitle(stringResource(Res.string.caldav_server))
                        Text(
                            stringResource(Res.string.sync_server_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.account == null) {
                    item { ConnectForm(state.form, viewModel) }
                } else {
                    item {
                        AccountCard(state, viewModel, onChangePassword = { changePassword = true })
                    }
                    taskListSection(
                        state,
                        viewModel,
                        onStop = { stopTaskList = it },
                        onStartCategory = { startCategory = it },
                    )
                    item {
                        OutlinedButton(
                            onClick = { confirmRemove = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) {
                            Text(
                                stringResource(Res.string.remove_account),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    stopTaskList?.let { list ->
        ConfirmDialog(
            text = stringResource(Res.string.task_list_stop_confirm, list.name),
            confirm = stringResource(Res.string.stop_syncing),
            onConfirm = {
                stopTaskList = null
                viewModel.stopSyncingTaskList(list.id)
            },
            onDismiss = { stopTaskList = null },
        )
    }
    startCategory?.let { category ->
        ConfirmDialog(
            text = stringResource(Res.string.task_category_start_confirm, category.name),
            confirm = stringResource(Res.string.start_syncing),
            onConfirm = {
                startCategory = null
                viewModel.startSyncingCategory(category)
            },
            onDismiss = { startCategory = null },
        )
    }
    syncToggle?.let { SyncToggleDialog(it, viewModel) }
    if (changePassword) ChangePasswordDialog(viewModel, onDismiss = { changePassword = false })
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.remove_account)) },
            text = { Text(stringResource(Res.string.remove_account_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.removeAccount()
                    }
                ) {
                    Text(stringResource(Res.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}
