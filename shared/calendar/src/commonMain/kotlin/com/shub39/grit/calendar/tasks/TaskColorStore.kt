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
package com.shub39.grit.calendar.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.data.CategoryColors
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.KeyValueEntity
import com.shub39.grit.core.tasks.TaskRepo
import com.shub39.grit.shared.ui.navigation.TaskColors
import grit.shared.calendar.generated.resources.Res
import grit.shared.calendar.generated.resources.task_color
import grit.shared.calendar.generated.resources.task_no_color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Task colors in the kv table of the calendar database, one row per colored task, so that the
 * upstream task schema stays untouched. A deleted task loses its color: SQLite can give its id to a
 * new task.
 */
@OptIn(FlowPreview::class)
@Single(binds = [TaskColors::class])
class TaskColorStore(db: CalendarDatabase, @Provided taskRepo: TaskRepo) : TaskColors {
    private val dao = db.calendarDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var pending: Int? = null

    override val colors: StateFlow<Map<Long, Int>> =
        dao.valuesFlow(PREFIX)
            .map { rows -> parse(rows.associate { it.key to it.value }) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // After task changes, remove the colors of deleted tasks. Fresh reads, not the flows: a new
        // task is saved before its color, so it is always in the task table by then.
        scope.launch {
            taskRepo.getTasksFlow().debounce(1_000).collect {
                val ids = taskRepo.getTasks().mapTo(HashSet()) { it.id }
                for (key in dao.keysWithPrefix(PREFIX)) {
                    val id = key.removePrefix(PREFIX).toLongOrNull() ?: continue
                    if (id !in ids) dao.deleteValue(key)
                }
            }
        }
    }

    /** Stops the background work. The app never needs it, tests close the database after it. */
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    override fun setColor(taskId: Long, color: Int?) {
        if (taskId <= 0) return
        scope.launch {
            if (color == null) dao.deleteValue(PREFIX + taskId)
            else dao.putValue(KeyValueEntity(PREFIX + taskId, color.toLong() and 0xFFFFFFFFL))
        }
    }

    override fun setPending(color: Int?) {
        pending = color
    }

    override fun assignPending(taskId: Long) {
        val color = pending
        pending = null
        if (color != null) setColor(taskId, color)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Picker(selected: Int?, onSelect: (Int?) -> Unit) {
        val colors = MaterialTheme.colorScheme
        val noColor = stringResource(Res.string.task_no_color)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(stringResource(Res.string.task_color), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No color: an empty circle
                Swatch(
                    fill = null,
                    selected = selected == null,
                    outline = colors.outline,
                    ring = colors.onSurface,
                    label = noColor,
                    onClick = { onSelect(null) },
                )
                for (argb in PALETTE) {
                    Swatch(
                        fill = Color(argb),
                        selected = selected == argb,
                        outline = colors.outline,
                        ring = colors.onSurface,
                        label = null,
                        onClick = { onSelect(argb) },
                    )
                }
            }
        }
    }

    @Composable
    private fun Swatch(
        fill: Color?,
        selected: Boolean,
        outline: Color,
        ring: Color,
        label: String?,
        onClick: () -> Unit,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
                    .clickable(onClick = onClick)
                    .semantics {
                        this.selected = selected
                        label?.let { contentDescription = it }
                    },
        ) {
            Box(
                modifier =
                    Modifier.size(26.dp)
                        .clip(CircleShape)
                        .then(
                            if (fill != null) Modifier.background(fill)
                            else Modifier.border(2.dp, outline, CircleShape)
                        )
            )
        }
    }

    companion object {
        /** kv key prefix, followed by the task id. The value is the ARGB color. */
        const val PREFIX = "task-color:"

        /** The same colors as the categories of the calendar. */
        val PALETTE: List<Int> = CategoryColors.palette.mapNotNull { CategoryColors.argb(it) }

        /** kv rows to colors by task id. Values are unsigned 32-bit ARGB. */
        fun parse(rows: Map<String, Long>): Map<Long, Int> =
            rows.entries
                .mapNotNull { (key, value) ->
                    key.removePrefix(PREFIX).toLongOrNull()?.let { it to value.toInt() }
                }
                .toMap()
    }
}
