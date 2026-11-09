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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.data.CategoryColors
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.calendar.ui.LightTopBar
import com.shub39.grit.calendar.ui.account.CategoryDialog
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
private val ValueShape = RoundedCornerShape(8.dp)

/** Number of steps in which the form is built, one per frame. */
private const val STAGES = 5

@Composable
fun EventEditorScreen(
    state: EditorState,
    viewModel: EventEditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestPermission = rememberNotificationPermissionRequest()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    // Build the form over several frames, top fields first: on a slow phone, one frame with the
    // whole form would stall the opening animation
    var stage by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (stage < STAGES) {
            withFrameNanos {}
            stage++
        }
    }

    var pickStartDate by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }
    var pickStartTime by remember { mutableStateOf(false) }
    var pickEndTime by remember { mutableStateOf(false) }
    var pickReminders by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            LightTopBar(
                title =
                    stringResource(
                        if (state.isNew) Res.string.new_event else Res.string.edit_event
                    ),
                navigation = {
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

            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { ErrorText(it) }

                if (stage >= 2)
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { value -> viewModel.update { it.copy(title = value) } },
                        label = { Text(stringResource(Res.string.title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                if (stage >= 3) {
                    CalendarRow(state) { id -> viewModel.update { it.copy(calendarId = id) } }
                    CategoryRow(
                        state = state,
                        onPick = { name -> viewModel.update { it.copy(category = name) } },
                        onCreate = viewModel::createCategory,
                    )
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
                }

                if (stage >= 4) {
                    HorizontalDivider()
                    RepeatRow(state) { choice -> viewModel.update { it.copy(repeat = choice) } }
                    if (viewModel.changesSeries(state)) {
                        Text(
                            text = stringResource(Res.string.series_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SettingRow(
                        label = stringResource(Res.string.reminders),
                        value =
                            if (state.reminders.isEmpty()) "—"
                            else
                                state.reminders
                                    .sorted()
                                    .map { reminderLabel(it) }
                                    .joinToString(", "),
                        onClick = { pickReminders = true },
                    )
                    HorizontalDivider()
                }

                if (stage >= 5) {
                    OutlinedTextField(
                        value = state.location,
                        onValueChange = { value -> viewModel.update { it.copy(location = value) } },
                        label = { Text(stringResource(Res.string.location)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = { value ->
                            viewModel.update { it.copy(description = value) }
                        },
                        label = { Text(stringResource(Res.string.description)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    )
                }
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
    if (pickReminders) {
        RemindersDialog(
            selected = state.reminders,
            onDismiss = { pickReminders = false },
            onConfirm = { reminders ->
                viewModel.update { it.copy(reminders = reminders) }
                pickReminders = false
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

/** A clickable value, lighter than a chip or a text field. */
@Composable
private fun ValueText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.clip(ValueShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
        ValueText(Formatting.dateMedium(date), onDate)
        if (time != null) ValueText(Formatting.time(time, is24Hr), onTime)
    }
}

/** A label and a value; a click opens a picker. */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(ValueShape)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        leading?.invoke()
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CalendarRow(state: EditorState, onPick: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.calendars.firstOrNull { it.id == state.calendarId }
    // Existing events stay in their calendar: moving needs a delete and a create on the server
    val canChoose = state.isNew && state.calendars.size > 1
    Box {
        SettingRow(
            label = stringResource(Res.string.calendar),
            value = selected?.name.orEmpty(),
            onClick = { if (canChoose) expanded = true },
        )
        if (expanded) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
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
}

@Composable
private fun ColorDot(argb: Int?, size: Int = 12) {
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .background(argb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun CategoryRow(
    state: EditorState,
    onPick: (String?) -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val selected = state.categories.firstOrNull { it.name == state.category }
    Box {
        SettingRow(
            label = stringResource(Res.string.category),
            value = state.category ?: stringResource(Res.string.no_category),
            onClick = { expanded = true },
            leading = { if (state.category != null) ColorDot(selected?.argb) },
        )
        if (expanded) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.no_category)) },
                    onClick = {
                        onPick(null)
                        expanded = false
                    },
                )
                for (option in state.categories) {
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        leadingIcon = { ColorDot(option.argb) },
                        onClick = {
                            onPick(option.name)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.new_category)) },
                    leadingIcon = {
                        Icon(vectorResource(Res.drawable.add), contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        creating = true
                    },
                )
            }
        }
    }
    if (creating) {
        CategoryDialog(
            title = stringResource(Res.string.new_category),
            initialName = "",
            initialColor = CategoryColors.palette.first(),
            nameEditable = true,
            onDismiss = { creating = false },
            onConfirm = { name, color ->
                onCreate(name, color)
                creating = false
            },
        )
    }
}

@Composable
private fun RepeatRow(state: EditorState, onPick: (RepeatChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            label = stringResource(Res.string.repeat),
            value = repeatLabel(state.repeat, state.customRule),
            onClick = { expanded = true },
        )
        if (expanded) {
            val choices =
                RepeatChoice.entries.filter {
                    it != RepeatChoice.Custom || state.customRule != null
                }
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
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
}

@Composable
private fun RemindersDialog(
    selected: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.reminders)) },
        text = {
            Column {
                for (minutes in (REMINDER_PRESETS + selected).distinct().sorted()) {
                    val checked = minutes in chosen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().clickable {
                                chosen = if (checked) chosen - minutes else chosen + minutes
                            },
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(reminderLabel(minutes), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
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
