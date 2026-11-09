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
package com.shub39.grit.caldav.ical

import kotlin.time.Instant

/** The fields of an event that Grit shows and edits. Read from the master VEVENT. */
data class EventInfo(
    val uid: String,
    val summary: String?,
    val location: String?,
    val description: String?,
    val start: IcalTime,
    val end: IcalTime,
    /** The RRULE value, for example `FREQ=WEEKLY;BYDAY=MO`, or null for a single event. */
    val rrule: String?,
    /** True if the series has moved, changed or removed occurrences (overrides, EXDATE, RDATE). */
    val hasExceptions: Boolean,
    /** Display reminders relative to the start, in minutes before the start. */
    val reminderMinutes: List<Int>,
    /** The first value of CATEGORIES. Grit gives an event at most one category. */
    val category: String?,
    /** The RFC 7986 COLOR value, a CSS color name. */
    val color: String?,
    val cancelled: Boolean,
    val sequence: Int,
    val lastModified: Instant?,
)

/** What the user enters in the event editor. */
data class EventDraft(
    val summary: String,
    val start: IcalTime,
    val end: IcalTime,
    val location: String? = null,
    val description: String? = null,
    val rrule: String? = null,
    val reminderMinutes: List<Int> = emptyList(),
    val category: String? = null,
    /** CSS color name of the category, written as RFC 7986 COLOR so that other users see it. */
    val color: String? = null,
)

/** One occurrence of an event in time. */
data class Occurrence(
    /** Start time. All-day occurrences start at midnight UTC of their date. */
    val start: Instant,
    /** End time (exclusive). All-day occurrences end at midnight UTC of the day after. */
    val end: Instant,
    val allDay: Boolean,
    /** The original start of this occurrence in its series, or null for a single event. */
    val originalStart: Instant?,
    /** True if a RECURRENCE-ID override changed this occurrence. */
    val isOverride: Boolean,
    val summary: String?,
    val location: String?,
    /** When reminders for this occurrence fire. */
    val alarms: List<Instant>,
)
