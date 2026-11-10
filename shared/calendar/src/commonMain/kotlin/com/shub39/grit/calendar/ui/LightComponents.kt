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
package com.shub39.grit.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Light versions of Material components for the calendar screens. Material's top app bar and
// segmented buttons handle scrolling, colors and animations that these screens do not use, and
// building them was a large part of the first frame of each screen.

/** A top bar: navigation icon, title and actions on one row below the status bar. */
@Composable
internal fun LightTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier =
            modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(12.dp))
        // Long titles, for example in French, shrink to fit. Clip, not Ellipsis: auto-size detects
        // an ellipsis only on Android. With one line, words that still do not fit are hidden whole.
        val titleStyle = MaterialTheme.typography.titleLarge
        BasicText(
            text = title,
            style =
                titleStyle.copy(fontWeight = FontWeight.Bold, color = LocalContentColor.current),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            autoSize =
                TextAutoSize.StepBased(
                    minFontSize = 14.sp,
                    maxFontSize = titleStyle.fontSize,
                    stepSize = 1.sp,
                ),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
}

private val SelectorShape = RoundedCornerShape(50)

/** A row of mutually exclusive options. */
@Composable
internal fun <T> LightSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier.height(40.dp).clip(SelectorShape).border(1.dp, colors.outline, SelectorShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (isSelected) colors.secondaryContainer else Color.Transparent
                        )
                        .clickable { onSelect(option) },
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onSecondaryContainer else colors.onSurface,
                )
            }
        }
    }
}
