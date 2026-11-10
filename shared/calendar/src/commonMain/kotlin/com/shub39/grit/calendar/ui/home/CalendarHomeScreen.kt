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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.Formatting
import com.shub39.grit.calendar.ui.LightSelector
import com.shub39.grit.calendar.ui.LightTopBar
import com.shub39.grit.shared.ui.components.GritBottomSheet
import grit.shared.calendar.generated.resources.*
import java.time.ZoneId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

private val RowShape = RoundedCornerShape(16.dp)

@Composable
fun CalendarHomeScreen(
    state: CalendarUiState,
    onAction: (CalendarAction) -> Unit,
    onOpenAccount: () -> Unit,
    onCreateEvent: (LocalDate, Int?) -> Unit,
    onEditEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onAction(CalendarAction.Opened) }
    var showFilter by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }

    // Surface sets the content color, so that texts follow the theme
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Nothing until the default view is known, so that the screen never switches views
        if (!state.ready) return@Surface
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                CalendarTopBar(
                    state,
                    onAction,
                    onOpenAccount,
                    onFilter = { showFilter = true },
                    onTitle = { showMonthPicker = true },
                )
                val defaultName = stringResource(Res.string.default_local_calendar)
                when {
                    !state.hasCalendars ->
                        EmptyPrompt(
                            onCreateLocal = {
                                onAction(CalendarAction.CreateLocalCalendar(defaultName))
                            },
                            onConnect = onOpenAccount,
                        )
                    // Pull to refresh only makes sense with a server
                    state.hasServer ->
                        PullToRefreshBox(
                            isRefreshing = state.syncing,
                            onRefresh = { onAction(CalendarAction.Refresh) },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            CalendarContent(state, onAction, onCreateEvent)
                        }
                    else -> CalendarContent(state, onAction, onCreateEvent)
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
                            if (state.mode == CalendarMode.Agenda) state.today
                            else state.selectedDate,
                            null,
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

    if (showMonthPicker) {
        MonthPickerDialog(
            current =
                when (state.mode) {
                    CalendarMode.Month -> state.month
                    CalendarMode.Week -> YearMonth(state.week.year, state.week.month)
                    CalendarMode.Agenda ->
                        YearMonth(state.agendaStart.year, state.agendaStart.month)
                },
            today = state.today,
            onPick = {
                showMonthPicker = false
                onAction(CalendarAction.JumpTo(it))
            },
            onDismiss = { showMonthPicker = false },
        )
    }
    if (showFilter) {
        CalendarFilterSheet(
            calendars = state.calendars,
            onDismiss = { showFilter = false },
            onToggle = { calendar, visible ->
                onAction(CalendarAction.SetCalendarVisible(calendar.id, visible, calendar.phone))
            },
        )
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

/**
 * The views stay composed and measured, and only the active one is placed. Switching between them
 * then costs no composition. The other views are composed a few frames after the first one, so that
 * opening the tab pays for one view only.
 */
@Composable
private fun CalendarContent(
    state: CalendarUiState,
    onAction: (CalendarAction) -> Unit,
    onCreateEvent: (LocalDate, Int?) -> Unit,
) {
    // The active view first; the two others one at a time, a few frames apart
    var built by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (built < CalendarMode.entries.size) {
            repeat(3) { withFrameNanos {} }
            built++
        }
    }
    val mode = state.mode
    val order = remember { listOf(mode) + CalendarMode.entries.filter { it != mode } }
    fun shown(view: CalendarMode) = view == mode || order.indexOf(view) < built
    Layout(
        content = {
            if (shown(CalendarMode.Month)) {
                Box(Modifier.layoutId(CalendarMode.Month)) {
                    MonthView(
                        month = state.month,
                        selectedDate = state.selectedDate,
                        today = state.today,
                        firstDayOfWeek = state.firstDayOfWeek,
                        monthData = state.monthData,
                        is24Hr = state.is24Hr,
                        onAction = onAction,
                    )
                }
            }
            if (shown(CalendarMode.Week)) {
                Box(Modifier.layoutId(CalendarMode.Week)) {
                    WeekPager(
                        week = state.week,
                        selectedDate = state.selectedDate,
                        today = state.today,
                        firstDayOfWeek = state.firstDayOfWeek,
                        weeks = state.weeks,
                        is24Hr = state.is24Hr,
                        canCreate = state.canCreate,
                        onWeekChange = { onAction(CalendarAction.ShowWeek(it)) },
                        onSelect = { onAction(CalendarAction.SelectDate(it)) },
                        onOpenEvent = { onAction(CalendarAction.OpenEvent(it)) },
                        onCreateAt = { date, hour -> onCreateEvent(date, hour) },
                    )
                }
            }
            if (shown(CalendarMode.Agenda)) {
                Box(Modifier.layoutId(CalendarMode.Agenda)) {
                    AgendaView(state.agenda, state.agendaStart, state.today, state.is24Hr, onAction)
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.associate { it.layoutId to it.measure(constraints) }
        val active = placeables[mode]
        layout(constraints.maxWidth, constraints.maxHeight) { active?.place(0, 0) }
    }
}

@Composable
private fun CalendarTopBar(
    state: CalendarUiState,
    onAction: (CalendarAction) -> Unit,
    onOpenAccount: () -> Unit,
    onFilter: () -> Unit,
    onTitle: () -> Unit,
) {
    Column {
        LightTopBar(
            title =
                when (state.mode) {
                    CalendarMode.Month -> Formatting.month(state.month)
                    CalendarMode.Week ->
                        Formatting.month(YearMonth(state.week.year, state.week.month))
                    CalendarMode.Agenda -> stringResource(Res.string.agenda)
                },
            onTitleClick = if (state.hasCalendars) onTitle else null,
            actions = {
                TextButton(onClick = { onAction(CalendarAction.Today) }) {
                    Text(stringResource(Res.string.today))
                }
                if (state.calendars.size > 1) {
                    IconButton(onClick = onFilter) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.filter_list),
                            contentDescription = stringResource(Res.string.shown_calendars),
                        )
                    }
                }
                IconButton(onClick = onOpenAccount) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.settings),
                        contentDescription = stringResource(Res.string.accounts),
                    )
                }
            },
        )
        if (state.hasCalendars) {
            LightSelector(
                options = CalendarMode.entries,
                selected = state.mode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            CalendarMode.Month -> Res.string.month
                            CalendarMode.Week -> Res.string.week
                            CalendarMode.Agenda -> Res.string.agenda
                        }
                    )
                },
                onSelect = { onAction(CalendarAction.SetMode(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
private fun EmptyPrompt(onCreateLocal: () -> Unit, onConnect: () -> Unit) {
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
            text = stringResource(Res.string.empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreateLocal) { Text(stringResource(Res.string.use_local_calendar)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onConnect) { Text(stringResource(Res.string.connect_title)) }
    }
}

/**
 * Each view gets only the fields it shows, so that a change elsewhere in the state (the mode, the
 * sync status) does not rebuild it.
 */
@Composable
private fun MonthView(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    firstDayOfWeek: kotlinx.datetime.DayOfWeek,
    monthData: MonthData,
    is24Hr: Boolean,
    onAction: (CalendarAction) -> Unit,
) {
    val selectedEvents = monthData.byDay[selectedDate].orEmpty()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "grid") {
            MonthPager(
                month = month,
                selectedDate = selectedDate,
                today = today,
                firstDayOfWeek = firstDayOfWeek,
                weeks = monthData.weeks,
                onMonthChange = { onAction(CalendarAction.ShowMonth(it)) },
                onSelect = { onAction(CalendarAction.SelectDate(it)) },
            )
        }
        item(key = "title") {
            Text(
                text = Formatting.dayTitle(selectedDate),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        if (selectedEvents.isEmpty()) {
            item(key = "empty") { EmptyDay() }
        } else {
            items(selectedEvents, key = { it.key }) { event ->
                EventRow(event, is24Hr, onClick = { onAction(CalendarAction.OpenEvent(event)) })
            }
        }
    }
}

@Composable
private fun AgendaView(
    agenda: List<Pair<LocalDate, List<DayEvent>>>,
    start: LocalDate,
    today: LocalDate,
    is24Hr: Boolean,
    onAction: (CalendarAction) -> Unit,
) {
    val listState = rememberLazyListState()
    // A jump to another month starts the list again from its top
    LaunchedEffect(start) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        if (agenda.isEmpty()) item { EmptyDay() }
        for ((date, events) in agenda) {
            item(key = "day-$date") {
                Text(
                    text = Formatting.dayTitle(date),
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (date == today) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(events, key = { "$date-${it.key}" }) { event ->
                EventRow(event, is24Hr, onClick = { onAction(CalendarAction.OpenEvent(event)) })
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
                .clip(RowShape)
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

/** Chooses which calendars the views show. */
@Composable
private fun CalendarFilterSheet(
    calendars: List<CalendarFilterItem>,
    onDismiss: () -> Unit,
    onToggle: (CalendarFilterItem, Boolean) -> Unit,
) {
    GritBottomSheet(onDismissRequest = onDismiss, padding = 16.dp) {
        Text(
            stringResource(Res.string.shown_calendars),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        for (calendar in calendars) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RowShape)
                        .clickable { onToggle(calendar, !calendar.visible) }
                        .padding(vertical = 4.dp),
            ) {
                Checkbox(checked = calendar.visible, onCheckedChange = null)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                    val detail =
                        when {
                            calendar.local -> stringResource(Res.string.on_this_device)
                            calendar.phone ->
                                calendar.detail ?: stringResource(Res.string.from_other_apps)
                            else -> null
                        }
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
