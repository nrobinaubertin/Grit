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
package com.shub39.grit.calendar.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.shared.ui.components.GritBottomSheet
import grit.shared.calendar.generated.resources.*
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun EventDetailSheet(
    details: EventDetailsUi,
    is24Hr: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDeleteOccurrence: () -> Unit,
    onCompleteTask: () -> Unit,
) {
    val event = details.event
    if (event.taskId != null) {
        TaskDetailSheet(event, is24Hr, onDismiss, onCompleteTask)
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }

    GritBottomSheet(onDismissRequest = onDismiss, padding = 24.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = event.title.ifBlank { "—" }, style = MaterialTheme.typography.headlineSmall)

            Text(text = dateLine(event, is24Hr), style = MaterialTheme.typography.bodyLarge)

            Row(verticalAlignment = Alignment.CenterVertically) {
                PatternSwatch(event.pattern, eventColor(event.color), Modifier.padding(end = 8.dp))
                Text(
                    text =
                        listOfNotNull(
                                event.calendarName,
                                if (!event.canWrite) stringResource(Res.string.read_only) else null,
                            )
                            .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            event.category?.let { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier.size(12.dp)
                                .clip(CircleShape)
                                .background(eventColor(event.color))
                    )
                    Text(
                        text = category,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            details.rrule?.let { DetailLine(Res.drawable.schedule, repeatText(it)) }
            event.location?.takeIf { it.isNotBlank() }?.let { DetailLine(Res.drawable.map, it) }
            details.description
                ?.takeIf { it.isNotBlank() }
                ?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }

            if (event.canWrite) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.edit))
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.delete))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(Res.string.delete_event_confirm)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (event.recurring && event.originalStart != null) {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                onDeleteOccurrence()
                            }
                        ) {
                            Text(stringResource(Res.string.delete_occurrence))
                        }
                    }
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        }
                    ) {
                        Text(
                            stringResource(
                                if (event.recurring) Res.string.delete_series else Res.string.delete
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

/** A task with a date: its title, its time, its category, and a button to mark it as done. */
@Composable
private fun TaskDetailSheet(
    event: DayEvent,
    is24Hr: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    GritBottomSheet(onDismissRequest = onDismiss, padding = 24.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = event.title.removePrefix(TASK_MARK).ifBlank { "—" },
                style = MaterialTheme.typography.headlineSmall,
            )
            val day = Formatting.dayTitle(event.startDay)
            val time = Formatting.time(event.begin, ZoneId.systemDefault(), is24Hr)
            Text(text = "$day · $time", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(12.dp).clip(CircleShape).background(eventColor(event.color))
                )
                Text(
                    text =
                        listOfNotNull(stringResource(Res.string.task), event.category)
                            .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(Res.string.mark_done))
            }
        }
    }
}

@Composable
private fun DetailLine(icon: org.jetbrains.compose.resources.DrawableResource, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = vectorResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** For example "Monday 9 November · 09:00 – 10:00", or the date range of an all-day event. */
@Composable
private fun dateLine(event: DayEvent, is24Hr: Boolean): String {
    val zone = ZoneId.systemDefault()
    if (event.allDay) {
        val start = Formatting.allDayDate(event.begin)
        val last = Formatting.allDayDate(event.end).minusDays(1)
        val first = Formatting.dayTitle(start.toKotlinLocalDate())
        return if (!last.isAfter(start)) "$first · ${stringResource(Res.string.all_day)}"
        else "$first – ${Formatting.dayTitle(last.toKotlinLocalDate())}"
    }
    val startDay = java.time.Instant.ofEpochMilli(event.begin).atZone(zone).toLocalDate()
    val endDay = java.time.Instant.ofEpochMilli(event.end).atZone(zone).toLocalDate()
    val start = Formatting.time(event.begin, zone, is24Hr)
    val end = Formatting.time(event.end, zone, is24Hr)
    return if (startDay == endDay) {
        "${Formatting.dayTitle(startDay.toKotlinLocalDate())} · $start – $end"
    } else {
        "${Formatting.dayTitle(startDay.toKotlinLocalDate())} $start – " +
            "${Formatting.dayTitle(endDay.toKotlinLocalDate())} $end"
    }
}

@Composable
internal fun repeatText(rrule: String): String =
    when (simpleFrequency(rrule)) {
        "DAILY" -> stringResource(Res.string.repeat_daily)
        "WEEKLY" -> stringResource(Res.string.repeat_weekly)
        "MONTHLY" -> stringResource(Res.string.repeat_monthly)
        "YEARLY" -> stringResource(Res.string.repeat_yearly)
        else -> stringResource(Res.string.repeat_custom, rrule)
    }

/** The frequency of a rule that has nothing but FREQ (and INTERVAL=1), else null. */
internal fun simpleFrequency(rrule: String): String? {
    val parts =
        rrule
            .split(';')
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=').uppercase() to it.substringAfter('=').uppercase() }
    val others = parts.keys - setOf("FREQ", "INTERVAL", "WKST")
    if (others.isNotEmpty() || (parts["INTERVAL"] ?: "1") != "1") return null
    return parts["FREQ"]
}
