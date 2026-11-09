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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.shared.ui.habit.daysStartingFrom
import grit.shared.calendar.generated.resources.*
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun CalendarHomeScreen(
    state: CalendarUiState,
    onAction: (CalendarAction) -> Unit,
    onOpenAccount: () -> Unit,
    onCreateEvent: (LocalDate) -> Unit,
    onEditEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onAction(CalendarAction.Opened) }

    // Surface sets the content color, so that texts follow the theme
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                CalendarTopBar(state, onAction, onOpenAccount)
                if (!state.hasAccount) {
                    ConnectPrompt(onOpenAccount)
                } else {
                    PullToRefreshBox(
                        isRefreshing = state.syncing,
                        onRefresh = { onAction(CalendarAction.Refresh) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (state.mode) {
                            CalendarMode.Month -> MonthView(state, onAction)
                            CalendarMode.Agenda -> AgendaView(state, onAction)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.canCreate,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier =
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            ) {
                MediumFloatingActionButton(
                    onClick = {
                        onCreateEvent(
                            if (state.mode == CalendarMode.Month) state.selectedDate
                            else state.today
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.add),
                        contentDescription = stringResource(Res.string.new_event),
                        modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize),
                    )
                }
            }
        }
    }

    state.details?.let { details ->
        EventDetailSheet(
            details = details,
            is24Hr = state.is24Hr,
            onDismiss = { onAction(CalendarAction.CloseEvent) },
            onEdit = {
                onAction(CalendarAction.CloseEvent)
                onEditEvent(details.event.eventId)
            },
            onDelete = { onAction(CalendarAction.DeleteEvent(details.event.eventId)) },
            onDeleteOccurrence = {
                details.event.originalStart?.let {
                    onAction(CalendarAction.DeleteOccurrence(details.event.eventId, it))
                }
            },
        )
    }
}

@Composable
private fun CalendarTopBar(
    state: CalendarUiState,
    onAction: (CalendarAction) -> Unit,
    onOpenAccount: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text =
                        if (state.mode == CalendarMode.Month) Formatting.month(state.month)
                        else stringResource(Res.string.agenda),
                    fontWeight = FontWeight.Bold,
                )
            },
            actions = {
                TextButton(onClick = { onAction(CalendarAction.Today) }) {
                    Text(stringResource(Res.string.today))
                }
                IconButton(onClick = onOpenAccount) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.settings),
                        contentDescription = stringResource(Res.string.accounts),
                    )
                }
            },
        )
        if (state.hasAccount) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val modes = CalendarMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { onAction(CalendarAction.SetMode(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(
                            stringResource(
                                if (mode == CalendarMode.Month) Res.string.month
                                else Res.string.agenda
                            )
                        )
                    }
                }
            }
            val status =
                when {
                    state.syncError != null ->
                        stringResource(Res.string.sync_error, state.syncError)
                    state.pendingChanges > 0 ->
                        stringResource(Res.string.pending_changes, state.pendingChanges)
                    else -> null
                }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (state.syncError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectPrompt(onOpenAccount: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = vectorResource(Res.drawable.calendar_month),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.connect_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.connect_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenAccount) { Text(stringResource(Res.string.connect)) }
    }
}

@Composable
private fun MonthView(state: CalendarUiState, onAction: (CalendarAction) -> Unit) {
    val calendarState =
        rememberCalendarState(
            startMonth = state.today.let { kotlinx.datetime.YearMonth(it.year - 20, 1) },
            endMonth = state.today.let { kotlinx.datetime.YearMonth(it.year + 20, 12) },
            firstVisibleMonth = state.month,
            firstDayOfWeek = state.firstDayOfWeek,
        )

    // Swiping the grid changes the month, and "Today" scrolls the grid back
    LaunchedEffect(calendarState) {
        snapshotFlow { calendarState.firstVisibleMonth.yearMonth }
            .distinctUntilChanged()
            .collect { onAction(CalendarAction.ShowMonth(it)) }
    }
    LaunchedEffect(state.month) {
        if (calendarState.firstVisibleMonth.yearMonth != state.month) {
            calendarState.animateScrollToMonth(state.month)
        }
    }

    val selectedEvents = state.eventsByDay[state.selectedDate].orEmpty()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            HorizontalCalendar(
                state = calendarState,
                contentPadding = PaddingValues(horizontal = 8.dp),
                monthHeader = {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        for (day in daysStartingFrom(state.firstDayOfWeek)) {
                            Text(
                                text = Formatting.weekday(day),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                dayContent = { day ->
                    DayCell(
                        day = day,
                        selected = day.date == state.selectedDate,
                        today = day.date == state.today,
                        events = state.eventsByDay[day.date].orEmpty(),
                        onClick = { onAction(CalendarAction.SelectDate(day.date)) },
                    )
                },
            )
        }
        item {
            Text(
                text = Formatting.dayTitle(state.selectedDate),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        if (selectedEvents.isEmpty()) {
            item { EmptyDay() }
        } else {
            items(selectedEvents, key = { it.instanceId }) { event ->
                EventRow(
                    event,
                    state.is24Hr,
                    onClick = { onAction(CalendarAction.OpenEvent(event)) },
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay,
    selected: Boolean,
    today: Boolean,
    events: List<DayEvent>,
    onClick: () -> Unit,
) {
    val inMonth = day.position == DayPosition.MonthDate
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.aspectRatio(0.9f)
                .padding(2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        selected -> colors.primaryContainer
                        today -> colors.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.date.day.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            color =
                when {
                    selected -> colors.onPrimaryContainer
                    today -> colors.primary
                    inMonth -> colors.onSurface
                    else -> colors.onSurface.copy(alpha = 0.38f)
                },
        )
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
            for (event in events.take(3)) {
                Box(
                    modifier =
                        Modifier.size(5.dp)
                            .clip(CircleShape)
                            .background(event.color?.let { Color(it) } ?: colors.primary)
                )
            }
        }
    }
}

@Composable
private fun AgendaView(state: CalendarUiState, onAction: (CalendarAction) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        if (state.agenda.isEmpty()) item { EmptyDay() }
        for ((date, events) in state.agenda) {
            item(key = "day-$date") {
                Text(
                    text = Formatting.dayTitle(date),
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (date == state.today) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(events, key = { "$date-${it.instanceId}" }) { event ->
                EventRow(
                    event,
                    state.is24Hr,
                    onClick = { onAction(CalendarAction.OpenEvent(event)) },
                )
            }
        }
        item {
            TextButton(
                onClick = { onAction(CalendarAction.LoadMoreAgenda) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(Res.string.load_more))
            }
        }
    }
}

@Composable
private fun EmptyDay() {
    Text(
        text = stringResource(Res.string.no_events),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun EventRow(event: DayEvent, is24Hr: Boolean, onClick: () -> Unit) {
    val color = event.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(6.dp).fillMaxHeight().background(color))
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).weight(1f)) {
            Text(
                text = event.title.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeText(event, is24Hr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.location
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}

@Composable
internal fun timeText(event: DayEvent, is24Hr: Boolean): String {
    if (event.allDay) return stringResource(Res.string.all_day)
    val zone = ZoneId.systemDefault()
    return Formatting.time(event.begin, zone, is24Hr) +
        " – " +
        Formatting.time(event.end, zone, is24Hr)
}
