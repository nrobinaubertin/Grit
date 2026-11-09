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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.ui.Formatting
import grit.shared.calendar.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun AccountScreen(state: AccountState, viewModel: AccountViewModel, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = { Text(stringResource(Res.string.accounts)) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(vectorResource(Res.drawable.nav_arrow_back), contentDescription = null)
                    }
                },
            )
            when {
                !state.loaded -> Unit
                state.account == null -> ConnectForm(state.form, viewModel)
                else -> AccountDetails(state, viewModel)
            }
        }
    }
}

@Composable
private fun ConnectForm(form: AccountForm, viewModel: AccountViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.connect_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = form.url,
            onValueChange = { value -> viewModel.updateForm { it.copy(url = value) } },
            label = { Text(stringResource(Res.string.server_url)) },
            placeholder = { Text(stringResource(Res.string.server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.username,
            onValueChange = { value -> viewModel.updateForm { it.copy(username = value) } },
            label = { Text(stringResource(Res.string.username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = { value -> viewModel.updateForm { it.copy(password = value) } },
            label = { Text(stringResource(Res.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        form.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = viewModel::connect,
            enabled = !form.connecting && form.url.isNotBlank() && form.username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (form.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(Res.string.connecting),
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(stringResource(Res.string.connect))
            }
        }
    }
}

@Composable
private fun AccountDetails(state: AccountState, viewModel: AccountViewModel) {
    val account = state.account ?: return
    var addByUrl by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(account.username, style = MaterialTheme.typography.titleMedium)
                    Text(account.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text =
                            account.lastSync?.let {
                                stringResource(Res.string.last_sync, Formatting.dateTime(it))
                            } ?: stringResource(Res.string.never_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    account.lastError?.let {
                        Text(
                            stringResource(Res.string.sync_error, it),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Button(onClick = viewModel::syncNow, enabled = !state.syncing) {
                            Text(
                                stringResource(
                                    if (state.syncing) Res.string.syncing else Res.string.sync_now
                                )
                            )
                        }
                        OutlinedButton(onClick = { changePassword = true }) {
                            Text(stringResource(Res.string.change_password))
                        }
                    }
                }
            }
        }

        item {
            Text(
                stringResource(Res.string.calendars),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        items(state.collections, key = { it.id }) { collection ->
            CollectionRow(collection, viewModel)
        }
        item {
            TextButton(onClick = { addByUrl = true }) {
                Text(stringResource(Res.string.add_by_url))
            }
        }
        item {
            OutlinedButton(
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text(
                    stringResource(Res.string.remove_account),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (addByUrl) {
        var url by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { addByUrl = false },
            title = { Text(stringResource(Res.string.add_by_url)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(Res.string.calendar_url)) },
                        singleLine = true,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        viewModel.addByUrl(url) { result ->
                            busy = false
                            error = result
                            if (result == null) addByUrl = false
                        }
                    },
                ) {
                    Text(stringResource(Res.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { addByUrl = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    if (changePassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changePassword = false },
            title = { Text(stringResource(Res.string.change_password)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        viewModel.changePassword(password)
                        changePassword = false
                    },
                ) {
                    Text(stringResource(Res.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { changePassword = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

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

@Composable
private fun CollectionRow(collection: CollectionEntity, viewModel: AccountViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(14.dp)
                        .clip(CircleShape)
                        .background(
                            collection.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        )
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    collection.displayName ?: collection.url,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val notes =
                    listOfNotNull(
                        if (!collection.canWrite) stringResource(Res.string.read_only) else null,
                        collection.lastError?.let { stringResource(Res.string.sync_error, it) },
                    )
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (collection.lastError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (collection.manual) {
                    TextButton(
                        onClick = { viewModel.removeCollection(collection.id) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(Res.string.remove))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(Res.string.show_in_calendar),
                    style = MaterialTheme.typography.labelSmall,
                )
                Switch(
                    checked = collection.visible,
                    onCheckedChange = { viewModel.setVisible(collection.id, it) },
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    stringResource(Res.string.sync_calendar),
                    style = MaterialTheme.typography.labelSmall,
                )
                Switch(
                    checked = collection.syncEnabled,
                    onCheckedChange = { viewModel.setSyncEnabled(collection.id, it) },
                )
            }
        }
    }
}
