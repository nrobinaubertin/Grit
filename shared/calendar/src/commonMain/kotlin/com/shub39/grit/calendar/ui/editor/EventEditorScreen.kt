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
package com.shub39.grit.calendar.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.shared.ui.components.GritTimePicker
import grit.shared.calendar.generated.resources.*
import java.time.ZoneId
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

private val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(
    state: EditorState,
    viewModel: EventEditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestPermission = rememberNotificationPermissionRequest()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    var pickStartDate by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var pickStartTime by remember { mutableStateOf(false) }
    var pickEndTime by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) Res.string.new_event else Res.string.edit_event
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            vectorResource(Res.drawable.close),
                            contentDescription = stringResource(Res.string.cancel),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (state.reminders.isNotEmpty()) requestPermission()
                            viewModel.save()
                        },
                        enabled = !state.loading && !state.saving,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(Res.string.save))
                    }
                },
            )
            if (state.loading) return@Column

            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.error?.let { ErrorText(it) }

                OutlinedTextField(
                    value = state.title,
                    onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                    label = { Text(stringResource(Res.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                CalendarPicker(state, enabled = state.isNew) { id ->
                    viewModel.update { it.copy(calendarId = id) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.all_day), modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.allDay,
                        onCheckedChange = { checked ->
                            viewModel.update { it.copy(allDay = checked) }
                        },
                    )
                }

                DateTimeRow(
                    label = stringResource(Res.string.starts),
                    date = state.startDate,
                    time = state.startTime.takeIf { !state.allDay },
                    is24Hr = state.is24Hr,
                    onDate = { pickStartDate = true },
                    onTime = { pickStartTime = true },
                )
                DateTimeRow(
                    label = stringResource(Res.string.ends),
                    date = state.endDate,
                    time = state.endTime.takeIf { !state.allDay },
                    is24Hr = state.is24Hr,
                    onDate = { pickEndDate = true },
                    onTime = { pickEndTime = true },
                )
                if (!state.allDay && state.zoneId != ZoneId.systemDefault().id) {
                    Text(
                        text = stringResource(Res.string.time_zone, state.zoneId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RepeatPicker(state) { choice -> viewModel.update { it.copy(repeat = choice) } }
                if (viewModel.changesSeries(state)) {
                    Text(
                        text = stringResource(Res.string.series_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    stringResource(Res.string.reminders),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = (REMINDER_PRESETS + state.reminders).distinct().sorted()
                    for (minutes in presets) {
                        val selected = minutes in state.reminders
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        reminders =
                                            if (selected) it.reminders - minutes
                                            else it.reminders + minutes
                                    )
                                }
                            },
                            label = { Text(reminderLabel(minutes)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = state.location,
                    onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                    label = { Text(stringResource(Res.string.location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.description,
                    onValueChange = { value -> viewModel.update { it.copy(description = value) } },
                    label = { Text(stringResource(Res.string.description)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
        }
    }

    if (pickStartDate || pickEndDate) {
        DateDialog(
            initial = if (pickStartDate) state.startDate else state.endDate,
            onDismiss = {
                pickStartDate = false
                pickEndDate = false
            },
            onPick = { date ->
                if (pickStartDate) viewModel.setStartDate(date)
                else viewModel.update { it.copy(endDate = date) }
                pickStartDate = false
                pickEndDate = false
            },
        )
    }
    if (pickStartTime || pickEndTime) {
        val initial = if (pickStartTime) state.startTime else state.endTime
        val timeState =
            rememberTimePickerState(
                initialHour = initial.hour,
                initialMinute = initial.minute,
                is24Hour = state.is24Hr,
            )
        GritTimePicker(
            onDismissRequest = {
                pickStartTime = false
                pickEndTime = false
            },
            state = timeState,
            onConfirm = {
                val time = LocalTime(timeState.hour, timeState.minute)
                if (pickStartTime) viewModel.setStartTime(time)
                else viewModel.update { it.copy(endTime = time) }
                pickStartTime = false
                pickEndTime = false
            },
        )
    }
}

@Composable
private fun ErrorText(error: EditorError) {
    Text(
        text =
            stringResource(
                when (error) {
                    EditorError.TitleRequired -> Res.string.title_required
                    EditorError.EndBeforeStart -> Res.string.end_before_start
                    EditorError.NoCalendar -> Res.string.no_writable_calendar
                    EditorError.SaveFailed -> Res.string.sync_error
                },
                "",
            ),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: LocalTime?,
    is24Hr: Boolean,
    onDate: () -> Unit,
    onTime: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        AssistChip(onClick = onDate, label = { Text(Formatting.dateMedium(date)) })
        if (time != null)
            AssistChip(onClick = onTime, label = { Text(Formatting.time(time, is24Hr)) })
    }
}

@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis ?: return@TextButton onDismiss()
                    onPick(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                }
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun CalendarPicker(state: EditorState, enabled: Boolean, onPick: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.calendars.firstOrNull { it.id == state.calendarId }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(Res.string.calendar)) },
            trailingIcon = { if (enabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            for (option in state.calendars) {
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onPick(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RepeatPicker(state: EditorState, onPick: (RepeatChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices =
        RepeatChoice.entries.filter { it != RepeatChoice.Custom || state.customRule != null }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = repeatLabel(state.repeat, state.customRule),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.repeat)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (choice in choices) {
                DropdownMenuItem(
                    text = { Text(repeatLabel(choice, state.customRule)) },
                    onClick = {
                        onPick(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun repeatLabel(choice: RepeatChoice, customRule: String?): String =
    when (choice) {
        RepeatChoice.None -> stringResource(Res.string.repeat_none)
        RepeatChoice.Daily -> stringResource(Res.string.repeat_daily)
        RepeatChoice.Weekly -> stringResource(Res.string.repeat_weekly)
        RepeatChoice.Monthly -> stringResource(Res.string.repeat_monthly)
        RepeatChoice.Yearly -> stringResource(Res.string.repeat_yearly)
        RepeatChoice.Custom -> stringResource(Res.string.repeat_custom, customRule.orEmpty())
    }

@Composable
private fun reminderLabel(minutes: Int): String =
    when {
        minutes == 0 -> stringResource(Res.string.reminder_at_start)
        minutes % 1440 == 0 -> stringResource(Res.string.reminder_days, minutes / 1440)
        minutes % 60 == 0 -> stringResource(Res.string.reminder_hours, minutes / 60)
        else -> stringResource(Res.string.reminder_minutes, minutes)
    }
