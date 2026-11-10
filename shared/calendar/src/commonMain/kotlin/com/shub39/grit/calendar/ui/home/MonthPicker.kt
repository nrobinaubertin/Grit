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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.shub39.grit.calendar.ui.Formatting
import grit.shared.calendar.generated.resources.Res
import grit.shared.calendar.generated.resources.arrow_back
import grit.shared.calendar.generated.resources.arrow_forward
import grit.shared.calendar.generated.resources.cancel
import grit.shared.calendar.generated.resources.next_year
import grit.shared.calendar.generated.resources.previous_year
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** Years before and after this year that the pickers offer. The pagers reach at least as far. */
internal const val JUMP_YEARS = 10

private val MonthShape = RoundedCornerShape(50)

/** Picks a month: a year with arrows, then a grid of the 12 months. */
@Composable
internal fun MonthPickerDialog(
    current: YearMonth,
    today: LocalDate,
    onPick: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    var year by remember { mutableIntStateOf(current.year) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }, enabled = year > today.year - JUMP_YEARS) {
                    Icon(
                        vectorResource(Res.drawable.arrow_back),
                        contentDescription = stringResource(Res.string.previous_year),
                    )
                }
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { year++ }, enabled = year < today.year + JUMP_YEARS) {
                    Icon(
                        vectorResource(Res.drawable.arrow_forward),
                        contentDescription = stringResource(Res.string.next_year),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in Month.entries.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (month in row) {
                            val shown = YearMonth(year, month)
                            val isCurrent = shown == current
                            val isToday = year == today.year && month == today.month
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier.weight(1f)
                                        .height(40.dp)
                                        .clip(MonthShape)
                                        .then(
                                            when {
                                                isCurrent -> Modifier.background(colors.primary)
                                                isToday ->
                                                    Modifier.border(
                                                        1.dp,
                                                        colors.primary,
                                                        MonthShape,
                                                    )
                                                else -> Modifier
                                            }
                                        )
                                        .clickable { onPick(shown) },
                            ) {
                                Text(
                                    Formatting.monthShort(month),
                                    color = if (isCurrent) colors.onPrimary else colors.onSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
