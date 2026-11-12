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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.shub39.grit.calendar.data.CategoryColors
import com.shub39.grit.calendar.data.PhoneCalendar
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.calendar.ui.LightSelector
import com.shub39.grit.calendar.ui.LightTopBar
import grit.shared.calendar.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** Calendars on this device, and the CalDAV account with its calendars. */
@Composable
fun AccountScreen(state: AccountState, viewModel: AccountViewModel, onBack: () -> Unit) {
    var newLocal by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteLocal by remember { mutableStateOf<CollectionEntity?>(null) }
    var addByUrl by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    var phoneDenied by remember { mutableStateOf(false) }
    val importUi by viewModel.import.collectAsState()
    val syncToggle by viewModel.syncToggle.collectAsState()
    var confirmStart by remember { mutableStateOf<CollectionEntity?>(null) }
    var confirmStop by remember { mutableStateOf<CollectionEntity?>(null) }
    val askCalendarPermission = rememberCalendarReadPermission { granted ->
        phoneDenied = !granted
        viewModel.onPhonePermission()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            LightTopBar(
                title = stringResource(Res.string.accounts),
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
                displaySection(state, viewModel)
                localSection(
                    state,
                    viewModel,
                    onNew = { newLocal = true },
                    onDelete = { deleteLocal = it },
                    onStartSyncing = { confirmStart = it },
                )
                if (state.phoneSupported) {
                    phoneSection(state, viewModel, phoneDenied, onAllow = askCalendarPermission)
                }
                categorySection(
                    state,
                    onNew = { newCategory = true },
                    onEdit = { editCategory = it },
                    onDelete = { deleteCategory = it },
                )
                item { SectionTitle(stringResource(Res.string.caldav_server)) }
                if (state.account == null) {
                    item { ConnectForm(state.form, viewModel) }
                } else {
                    serverSection(
                        state,
                        viewModel,
                        onAddByUrl = { addByUrl = true },
                        onChangePassword = { changePassword = true },
                        onRemove = { confirmRemove = true },
                        onStopSyncing = { confirmStop = it },
                    )
                }
                importSection(onChoose = viewModel::pickIcsFile)
            }
        }
    }

    if (newLocal) NewLocalCalendarDialog(viewModel, onDismiss = { newLocal = false })
    if (newCategory) {
        CategoryDialog(
            title = stringResource(Res.string.new_category),
            initialName = "",
            initialColor = CategoryColors.palette.first(),
            nameEditable = true,
            onDismiss = { newCategory = false },
            onConfirm = { name, color ->
                viewModel.createCategory(name, color)
                newCategory = false
            },
        )
    }
    editCategory?.let { category ->
        CategoryDialog(
            title = category.name,
            initialName = category.name,
            initialColor = category.color,
            nameEditable = false,
            onDismiss = { editCategory = null },
            onConfirm = { _, color ->
                viewModel.setCategoryColor(category.name, color)
                editCategory = null
            },
        )
    }
    deleteCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deleteCategory = null },
            text = { Text(stringResource(Res.string.delete_category_confirm, category.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCategory(category.name)
                        deleteCategory = null
                    }
                ) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCategory = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
    deleteLocal?.let { calendar ->
        AlertDialog(
            onDismissRequest = { deleteLocal = null },
            text = {
                Text(
                    stringResource(
                        Res.string.delete_local_calendar_confirm,
                        calendar.displayName.orEmpty(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLocalCalendar(calendar.id)
                        deleteLocal = null
                    }
                ) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteLocal = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
    importUi?.let { ImportDialog(it, state, viewModel) }
    confirmStart?.let { calendar ->
        ConfirmDialog(
            text = stringResource(Res.string.start_syncing_confirm, calendar.displayName.orEmpty()),
            confirm = stringResource(Res.string.start_syncing),
            onConfirm = {
                confirmStart = null
                viewModel.startSyncing(calendar.id)
            },
            onDismiss = { confirmStart = null },
        )
    }
    confirmStop?.let { calendar ->
        ConfirmDialog(
            text = stringResource(Res.string.stop_syncing_confirm, calendar.displayName.orEmpty()),
            confirm = stringResource(Res.string.stop_syncing),
            onConfirm = {
                confirmStop = null
                viewModel.stopSyncing(calendar)
            },
            onDismiss = { confirmStop = null },
        )
    }
    syncToggle?.let { toggle ->
        AlertDialog(
            onDismissRequest = { if (toggle !is SyncToggleUi.Running) viewModel.closeSyncToggle() },
            text = {
                when (toggle) {
                    SyncToggleUi.Running ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                stringResource(Res.string.sync_toggle_running),
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    is SyncToggleUi.Done -> Text(toggle.message)
                    is SyncToggleUi.Failed -> Text(toggle.message)
                }
            },
            confirmButton = {
                if (toggle !is SyncToggleUi.Running) {
                    TextButton(onClick = viewModel::closeSyncToggle) {
                        Text(stringResource(Res.string.ok))
                    }
                }
            },
        )
    }
    if (addByUrl) AddByUrlDialog(viewModel, onDismiss = { addByUrl = false })
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

@Composable
private fun ConfirmDialog(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun LazyListScope.localSection(
    state: AccountState,
    viewModel: AccountViewModel,
    onNew: () -> Unit,
    onDelete: (CollectionEntity) -> Unit,
    onStartSyncing: (CollectionEntity) -> Unit,
) {
    item {
        Column {
            SectionTitle(stringResource(Res.string.on_this_device))
            Text(
                stringResource(Res.string.on_this_device_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(state.localCalendars, key = { "local-${it.id}" }) { calendar ->
        CalendarRow(
            collection = calendar,
            onVisible = { viewModel.setVisible(calendar.id, it) },
            // Only with a server to sync with
            syncAction =
                if (state.account != null) {
                    stringResource(Res.string.start_syncing) to { onStartSyncing(calendar) }
                } else null,
            trailing = {
                IconButton(onClick = { onDelete(calendar) }) {
                    Icon(
                        vectorResource(Res.drawable.delete),
                        contentDescription = stringResource(Res.string.delete),
                    )
                }
            },
        )
    }
    item { TextButton(onClick = onNew) { Text(stringResource(Res.string.new_local_calendar)) } }
}

/** Choices about how the calendar looks. */
private fun LazyListScope.displaySection(state: AccountState, viewModel: AccountViewModel) {
    item {
        Column {
            SectionTitle(stringResource(Res.string.display))
            Text(
                stringResource(Res.string.default_view_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.default_view),
                style = MaterialTheme.typography.bodyLarge,
            )
            // The same order as the selector of the calendar: month, week, agenda
            LightSelector(
                options = listOf(0, 1, 2),
                selected = state.defaultView,
                label = { view ->
                    stringResource(
                        when (view) {
                            1 -> Res.string.week
                            2 -> Res.string.agenda
                            else -> Res.string.month
                        }
                    )
                },
                onSelect = viewModel::setDefaultView,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LazyListScope.importSection(onChoose: () -> Unit) {
    item {
        Column {
            SectionTitle(stringResource(Res.string.import_title))
            Text(
                stringResource(Res.string.import_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { TextButton(onClick = onChoose) { Text(stringResource(Res.string.import_choose_file)) } }
}

/** One dialog for every step of an import: choose the calendar, wait, see the result. */
@Composable
private fun ImportDialog(step: ImportUi, state: AccountState, viewModel: AccountViewModel) {
    val onThisDevice = stringResource(Res.string.on_this_device)
    val newName = (step as? ImportUi.Choose)?.newCalendarName
    // Null stands for a new calendar on this device
    val targets: List<Pair<Long?, String>> =
        listOfNotNull(newName?.let { null to stringResource(Res.string.import_new_calendar, it) }) +
            state.localCalendars.map { it.id to "${it.displayName.orEmpty()} · $onThisDevice" } +
            state.collections
                .filter { it.canWrite && it.supportsEvents }
                .map { it.id to (it.displayName ?: it.url) }
    // A second import of a phone calendar goes into the calendar of the first one
    val initial =
        state.localCalendars.firstOrNull { newName != null && it.displayName == newName }?.id
            ?: targets.firstOrNull()?.first
    var chosen by remember(step) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (step !is ImportUi.Importing) viewModel.closeImport() },
        title = { Text(stringResource(Res.string.import_title)) },
        text = {
            when (step) {
                is ImportUi.Choose ->
                    if (targets.isEmpty()) {
                        Text(stringResource(Res.string.import_no_target))
                    } else {
                        Column {
                            Text(stringResource(Res.string.import_into, step.fileName, step.events))
                            for ((id, label) in targets) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { chosen = id },
                                ) {
                                    RadioButton(selected = chosen == id, onClick = { chosen = id })
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                ImportUi.Importing ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            stringResource(Res.string.import_running),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                is ImportUi.Done ->
                    Column {
                        Text(stringResource(Res.string.import_done, step.added, step.updated))
                        if (step.skipped > 0) {
                            Text(stringResource(Res.string.import_skipped, step.skipped))
                        }
                    }
                is ImportUi.Failed -> Text(step.message)
            }
        },
        confirmButton = {
            when (step) {
                is ImportUi.Choose ->
                    if (targets.isNotEmpty()) {
                        TextButton(onClick = { viewModel.importInto(chosen) }) {
                            Text(stringResource(Res.string.import_action))
                        }
                    }
                ImportUi.Importing -> Unit
                else ->
                    TextButton(onClick = viewModel::closeImport) {
                        Text(stringResource(Res.string.ok))
                    }
            }
        },
        dismissButton = {
            if (step is ImportUi.Choose) {
                TextButton(onClick = viewModel::closeImport) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        },
    )
}

/** The calendars that other apps keep on the phone, which Grit can import. */
private fun LazyListScope.phoneSection(
    state: AccountState,
    viewModel: AccountViewModel,
    denied: Boolean,
    onAllow: () -> Unit,
) {
    item {
        Column {
            SectionTitle(stringResource(Res.string.from_other_apps))
            Text(
                stringResource(Res.string.from_other_apps_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!state.phonePermission) {
        item { TextButton(onClick = onAllow) { Text(stringResource(Res.string.show_other_apps)) } }
        if (denied) {
            item {
                Text(
                    stringResource(Res.string.calendar_permission_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    if (state.phoneCalendars.isEmpty()) {
        item {
            Text(
                stringResource(Res.string.no_other_apps),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    items(state.phoneCalendars, key = { "phone-${it.id}" }) { calendar ->
        PhoneCalendarRow(calendar) { viewModel.importPhoneCalendar(calendar) }
    }
}

@Composable
private fun PhoneCalendarRow(calendar: PhoneCalendar, onImport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(calendar.color)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                calendar.accountName
                    ?.takeIf { it != calendar.name }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            TextButton(onClick = onImport) { Text(stringResource(Res.string.import_action)) }
        }
    }
}

private fun LazyListScope.categorySection(
    state: AccountState,
    onNew: () -> Unit,
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    item {
        Column {
            SectionTitle(stringResource(Res.string.categories))
            Text(
                stringResource(Res.string.categories_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(state.categories, key = { "category-${it.name}" }) { category ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { onEdit(category) }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            ) {
                ColorDot(CategoryColors.argb(category.color))
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                IconButton(onClick = { onDelete(category) }) {
                    Icon(
                        vectorResource(Res.drawable.delete),
                        contentDescription = stringResource(Res.string.delete),
                    )
                }
            }
        }
    }
    item { TextButton(onClick = onNew) { Text(stringResource(Res.string.new_category)) } }
}

private fun LazyListScope.serverSection(
    state: AccountState,
    viewModel: AccountViewModel,
    onAddByUrl: () -> Unit,
    onChangePassword: () -> Unit,
    onRemove: () -> Unit,
    onStopSyncing: (CollectionEntity) -> Unit,
) {
    val account = state.account ?: return
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
                    OutlinedButton(onClick = onChangePassword) {
                        Text(stringResource(Res.string.change_password))
                    }
                }
            }
        }
    }
    items(state.collections, key = { it.id }) { collection ->
        CalendarRow(
            collection = collection,
            onVisible = { viewModel.setVisible(collection.id, it) },
            syncAction =
                if (collection.syncEnabled) {
                    stringResource(Res.string.stop_syncing) to { onStopSyncing(collection) }
                } else {
                    // Paused by the former "Sync" switch
                    stringResource(Res.string.start_syncing) to
                        {
                            viewModel.startSyncing(collection.id)
                        }
                },
            trailing = null,
            onRemove =
                if (collection.manual) ({ viewModel.removeCollection(collection.id) }) else null,
        )
    }
    item { TextButton(onClick = onAddByUrl) { Text(stringResource(Res.string.add_by_url)) } }
    item {
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(Res.string.remove_account), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ConnectForm(form: AccountForm, viewModel: AccountViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun CalendarRow(
    collection: CollectionEntity,
    onVisible: (Boolean) -> Unit,
    /** "Start syncing" or "Stop syncing", with its action. */
    syncAction: Pair<String, () -> Unit>?,
    trailing: (@Composable () -> Unit)?,
    onRemove: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
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
                if (syncAction != null || onRemove != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        syncAction?.let { (label, action) ->
                            TextButton(onClick = action, contentPadding = PaddingValues(0.dp)) {
                                Text(label)
                            }
                        }
                        if (onRemove != null) {
                            TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(Res.string.remove))
                            }
                        }
                    }
                }
            }
            LabeledSwitch(
                stringResource(Res.string.show_in_calendar),
                collection.visible,
                onVisible,
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorDot(color: Int?, size: Int = 14) {
    Box(
        modifier =
            Modifier.size(size.dp)
                .clip(CircleShape)
                .background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun NewLocalCalendarDialog(viewModel: AccountViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_local_calendar)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.calendar_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    viewModel.createLocalCalendar(name)
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/** Name and color of a category. The name cannot change once events use it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryDialog(
    title: String,
    initialName: String,
    initialColor: String,
    nameEditable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    val outline = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (nameEditable) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.calendar_name)) },
                        singleLine = true,
                    )
                }
                Text(stringResource(Res.string.color), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in (CategoryColors.palette + initialColor).distinct()) {
                        Box(
                            modifier =
                                Modifier.size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        CategoryColors.argb(option)?.let { Color(it) } ?: Color.Gray
                                    )
                                    .then(
                                        if (option == color)
                                            Modifier.border(3.dp, outline, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { color = option }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), color) }) {
                Text(stringResource(if (nameEditable) Res.string.create else Res.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun AddByUrlDialog(viewModel: AccountViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                        if (result == null) onDismiss()
                    }
                },
            ) {
                Text(stringResource(Res.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun ChangePasswordDialog(viewModel: AccountViewModel, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
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
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
