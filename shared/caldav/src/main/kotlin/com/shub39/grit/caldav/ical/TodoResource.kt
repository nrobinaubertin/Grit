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

import java.io.StringReader
import java.io.StringWriter
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.Temporal
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.parameter.RelType
import net.fortuna.ical4j.model.parameter.Related
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.Action
import net.fortuna.ical4j.model.property.Completed
import net.fortuna.ical4j.model.property.Created
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.Due
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.PercentComplete
import net.fortuna.ical4j.model.property.RelatedTo
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.model.property.XProperty

/** The fields of a to-do that Grit uses. */
data class TodoInfo(
    val uid: String,
    val summary: String?,
    /** COMPLETED or CANCELLED status, a COMPLETED time, or 100 % done. */
    val done: Boolean,
    /**
     * DUE as a local time in the device zone, or null. A due date without a time is at
     * [TodoResource.DATE_ONLY_TIME].
     */
    val due: LocalDateTime?,
    /** UID of the parent to-do (RELATED-TO with RELTYPE=PARENT, the default), or null. */
    val parentUid: String?,
    /** X-APPLE-SORT-ORDER, which Apple Reminders and other task apps use for the order. */
    val sortOrder: Long?,
    val lastModified: Instant?,
    /** CREATED, or DTSTAMP if the to-do has no CREATED. It orders to-dos without [sortOrder]. */
    val created: Instant? = null,
)

/** What Grit writes into a to-do. */
data class TodoDraft(
    val summary: String,
    val done: Boolean,
    /** Local date and time in the device zone, or null. Not written for a done to-do. */
    val due: LocalDateTime?,
    val parentUid: String?,
    val sortOrder: Int,
)

/**
 * One CalDAV to-do resource (one VTODO). Edits return a new resource, change only the fields that
 * differ, and keep every property that Grit does not know, so that other task apps lose nothing.
 */
class TodoResource private constructor(private val calendar: Calendar) {

    private val todo: VToDo?
        get() = calendar.getComponents<VToDo>(Component.VTODO).firstOrNull()

    val uid: String?
        get() = todo?.text(Property.UID)

    /** The fields of the to-do, with times in [deviceZone]. Null if the resource has no VTODO. */
    fun info(deviceZone: ZoneId): TodoInfo? {
        val todo = todo ?: return null
        val status = todo.text(Property.STATUS)?.uppercase()
        val percent = todo.text(Property.PERCENT_COMPLETE)?.toIntOrNull()
        return TodoInfo(
            uid = todo.text(Property.UID) ?: "",
            summary = todo.text(Property.SUMMARY),
            done =
                status == Status.VALUE_COMPLETED ||
                    status == Status.VALUE_CANCELLED ||
                    todo.getProperty<Property>(Property.COMPLETED).isPresent ||
                    percent == 100,
            due = todo.due(deviceZone),
            parentUid = todo.parentUid(),
            sortOrder = todo.text(SORT_ORDER_NAME)?.trim()?.toLongOrNull(),
            lastModified =
                todo
                    .getProperty<LastModified>(Property.LAST_MODIFIED)
                    .map { it.date.toKotlinInstant() }
                    .orElse(null),
            created =
                todo
                    .getProperty<Created>(Property.CREATED)
                    .map { it.date.toKotlinInstant() }
                    .orElse(null)
                    ?: todo
                        .getProperty<DtStamp>(Property.DTSTAMP)
                        .map { it.date.toKotlinInstant() }
                        .orElse(null),
        )
    }

    /**
     * Writes [draft] into the to-do. Fields that already have the value of [draft] stay as they
     * are, so a due date without a time, for example, survives an edit of the title. A done to-do
     * keeps its due date. [now] becomes LAST-MODIFIED. Returns this resource unchanged if nothing
     * differs.
     */
    fun edit(draft: TodoDraft, deviceZone: ZoneId, now: Instant): TodoResource {
        val previous = info(deviceZone) ?: throw IllegalStateException("No VTODO to edit")
        val copy = parse(toIcs())
        val todo = copy.todo!!
        val changed = todo.apply(draft, previous, now)
        if (!changed) return this
        todo.touch(now, bumpSequence = true)
        return copy
    }

    /** Serializes the resource to iCalendar text with CRLF line endings. */
    fun toIcs(): String {
        val writer = StringWriter()
        CalendarOutputter(false).output(calendar, writer)
        return writer.toString()
    }

    companion object {
        /** The time Grit gives to a due date that has no time. */
        val DATE_ONLY_TIME: LocalTime = LocalTime.of(9, 0)

        private const val PRODUCT_ID = "-//Grit//Tasks//EN"

        /** Parses one CalDAV resource. Throws if the text is not iCalendar. */
        fun parse(ics: String): TodoResource =
            TodoResource(CalendarBuilder().build(StringReader(ics)))

        /** True if [ics] holds a VTODO. A calendar can hold events and to-dos side by side. */
        fun isTodo(ics: String): Boolean = ics.contains("BEGIN:VTODO")

        /** Creates a new to-do. [uid] must not contain line breaks. */
        fun create(uid: String, draft: TodoDraft, now: Instant): TodoResource {
            val stamp = IcalTime.Utc(now).value()
            val ics =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:$PRODUCT_ID\r\n" +
                    "BEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:$stamp\r\nCREATED:$stamp\r\n" +
                    "END:VTODO\r\nEND:VCALENDAR\r\n"
            val resource = parse(ics)
            val todo = resource.todo!!
            todo.apply(draft, TodoInfo(uid, null, false, null, null, null, null), now)
            todo.touch(now, bumpSequence = false)
            todo.add<VToDo>(Sequence(0))
            return resource
        }
    }
}

// --- reading -------------------------------------------------------------------------------

/** The order property of Apple Reminders, which other task apps read and write too. */
private const val SORT_ORDER_NAME = "X-APPLE-SORT-ORDER"

private fun VToDo.text(name: String): String? =
    getProperty<Property>(name).orElse(null)?.value?.takeIf { it.isNotEmpty() }

private fun VToDo.due(deviceZone: ZoneId): LocalDateTime? {
    val due = getProperty<Due<Temporal>>(Property.DUE).orElse(null) ?: return null
    val tzId = due.getParameter<TzId>(Parameter.TZID).orElse(null)?.value
    return when (val time = due.date.toIcalTime(tzId)) {
        is IcalTime.Date ->
            time.date.toJavaLocalDate().atTime(TodoResource.DATE_ONLY_TIME).toKotlinLocalDateTime()
        is IcalTime.Floating -> time.dateTime
        else ->
            time
                .toInstant(deviceZone)
                .toJavaInstant()
                .atZone(deviceZone)
                .toLocalDateTime()
                .toKotlinLocalDateTime()
    }
}

/** RELATED-TO without RELTYPE means a parent too (RFC 5545). */
private fun Property.isParentLink(): Boolean {
    val type = getParameter<RelType>(Parameter.RELTYPE).orElse(null)?.value
    return type == null || type.equals("PARENT", ignoreCase = true)
}

private fun VToDo.parentUid(): String? =
    getProperties<Property>(Property.RELATED_TO)
        .firstOrNull { it.isParentLink() }
        ?.value
        ?.takeIf { it.isNotBlank() }

/** The alarm that Grit writes: a display alarm at the due time. */
private fun VAlarm.isAtDue(): Boolean {
    val trigger = getProperty<Trigger>(Property.TRIGGER).orElse(null) ?: return false
    val relatedToEnd =
        trigger
            .getParameter<Related>(Parameter.RELATED)
            .orElse(null)
            ?.value
            .equals("END", ignoreCase = true)
    val duration = trigger.duration ?: return false
    return relatedToEnd && duration is Duration && duration.isZero
}

// --- writing -------------------------------------------------------------------------------

/** Writes the fields of [draft] that differ from [previous]. Returns true if anything changed. */
private fun VToDo.apply(draft: TodoDraft, previous: TodoInfo, now: Instant): Boolean {
    var changed = false
    if (draft.summary != previous.summary.orEmpty()) {
        removeAll<VToDo>(Property.SUMMARY)
        if (draft.summary.isNotEmpty()) add<VToDo>(Summary(draft.summary))
        changed = true
    }
    if (draft.done != previous.done) {
        removeAll<VToDo>(Property.STATUS, Property.COMPLETED, Property.PERCENT_COMPLETE)
        if (draft.done) {
            add<VToDo>(Status(Status.VALUE_COMPLETED))
            add<VToDo>(Completed(now.toJavaInstant()))
            add<VToDo>(PercentComplete(100))
        } else {
            add<VToDo>(Status(Status.VALUE_NEEDS_ACTION))
        }
        changed = true
    }
    // A done task keeps its due date for the others, even though Grit clears the reminder
    if (!draft.done && draft.due != previous.due) {
        removeAll<VToDo>(Property.DUE, Property.DURATION)
        getComponents<VAlarm>(Component.VALARM)
            .filter { it.isAtDue() }
            .forEach { remove<VToDo>(it) }
        val due = draft.due
        if (due != null) {
            // Floating: the same wall-clock time for everybody, as Grit stores it
            add<VToDo>(Due<Temporal>(due.toJavaLocalDateTime()))
            // DTSTART must not be after DUE
            val start = getProperty<Property>(Property.DTSTART).orElse(null)
            if (start != null && start.value > due.toJavaLocalDateTime().icalText()) {
                remove<VToDo>(start)
            }
            val alarm = VAlarm()
            alarm.add<VAlarm>(Trigger(ParameterList(listOf(Related.END)), Duration.ZERO))
            alarm.add<VAlarm>(Action("DISPLAY"))
            alarm.add<VAlarm>(Description(draft.summary.ifBlank { "Reminder" }))
            add<VToDo>(alarm)
        }
        changed = true
    }
    if (draft.parentUid != previous.parentUid) {
        getProperties<Property>(Property.RELATED_TO)
            .filter { it.isParentLink() }
            .forEach { remove<VToDo>(it) }
        if (draft.parentUid != null) add<VToDo>(RelatedTo(draft.parentUid))
        changed = true
    }
    if (draft.sortOrder.toLong() != previous.sortOrder) {
        removeAll<VToDo>(SORT_ORDER_NAME)
        add<VToDo>(XProperty(SORT_ORDER_NAME, draft.sortOrder.toString()))
        changed = true
    }
    return changed
}

private fun java.time.LocalDateTime.icalText(): String =
    "%04d%02d%02dT%02d%02d%02d".format(year, monthValue, dayOfMonth, hour, minute, second)

/** Updates DTSTAMP and LAST-MODIFIED, and increments SEQUENCE. */
private fun VToDo.touch(now: Instant, bumpSequence: Boolean) {
    val instant = now.toJavaInstant()
    replace<VToDo>(DtStamp(instant))
    replace<VToDo>(LastModified(instant))
    if (bumpSequence) {
        val sequence = getProperty<Sequence>(Property.SEQUENCE).map { it.sequenceNo }.orElse(0)
        replace<VToDo>(Sequence(sequence + 1))
    }
}
