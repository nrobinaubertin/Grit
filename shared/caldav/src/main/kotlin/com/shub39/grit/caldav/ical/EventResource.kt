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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.util.TreeMap
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TextList
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.Related
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.Action
import net.fortuna.ical4j.model.property.Categories
import net.fortuna.ical4j.model.property.Color
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Duration as DurationProperty
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Trigger

/**
 * One CalDAV event resource: a master VEVENT plus its RECURRENCE-ID overrides, which all share one
 * UID. Edits return a new resource and keep every property that Grit does not know, so that other
 * clients lose nothing.
 */
class EventResource private constructor(private val calendar: Calendar) {

    private val events: List<VEvent>
        get() = calendar.getComponents<VEvent>(Component.VEVENT)

    private val master: VEvent?
        get() = events.firstOrNull { it.recurrenceIdProperty() == null }

    private val overrides: List<VEvent>
        get() = events.filter { it.recurrenceIdProperty() != null }

    val uid: String?
        get() = events.firstNotNullOfOrNull { it.text(Property.UID) }

    /** The fields of the master event, or of the first override if there is no master. */
    fun info(): EventInfo? {
        val event = master ?: overrides.firstOrNull() ?: return null
        val dtStart = event.dtStart() ?: return null
        val start = dtStart.date.toIcalTime(dtStart.tzId())
        return EventInfo(
            uid = event.text(Property.UID) ?: "",
            summary = event.text(Property.SUMMARY),
            location = event.text(Property.LOCATION),
            description = event.text(Property.DESCRIPTION),
            start = start,
            end = event.endTime(dtStart),
            rrule = event.getProperty<RRule<Temporal>>(Property.RRULE).orElse(null)?.value,
            hasExceptions =
                overrides.isNotEmpty() ||
                    event.getProperties<Property>(Property.EXDATE, Property.RDATE).isNotEmpty(),
            reminderMinutes = event.simpleReminders().map { it.second }.sorted(),
            category = event.category(),
            color = event.text(Color.PROPERTY_NAME),
            cancelled = event.isCancelled(),
            sequence =
                event.getProperty<Sequence>(Property.SEQUENCE).map { it.sequenceNo }.orElse(0),
            lastModified =
                event
                    .getProperty<LastModified>(Property.LAST_MODIFIED)
                    .map { it.date.toKotlinInstant() }
                    .orElse(null),
        )
    }

    /**
     * Expands the event into the occurrences that overlap
     * [from, to). RECURRENCE-ID overrides replace or move occurrences, and cancelled ones are left out. All-day dates and floating times are placed in [deviceZone].
     */
    fun expand(
        from: Instant,
        to: Instant,
        deviceZone: ZoneId,
        limit: Int = MAX_OCCURRENCES,
    ): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        val overrides = overrides
        val master = master
        if (master != null && !master.isCancelled()) {
            val start = master.dtStart()?.date?.normalize(deviceZone)
            if (start != null) {
                val length = master.length(start, deviceZone)
                val overridden =
                    overrides
                        .mapNotNull {
                            it.recurrenceIdProperty()?.date?.normalize(deviceZone)?.key()
                        }
                        .toSet()
                val recurring = master.isRecurring()
                for (occurrenceStart in
                    master.startTimes(start, length, from, to, deviceZone, limit)) {
                    if (occurrenceStart.key() in overridden) continue
                    result +=
                        master.occurrence(
                            occurrenceStart,
                            occurrenceStart.plusAmount(length),
                            originalStart = if (recurring) occurrenceStart else null,
                            isOverride = false,
                            deviceZone,
                        )
                }
            }
        }
        for (override in overrides) {
            if (override.isCancelled()) continue
            val start = override.dtStart()?.date?.normalize(deviceZone) ?: continue
            result +=
                override.occurrence(
                    start,
                    start.plusAmount(override.length(start, deviceZone)),
                    originalStart = override.recurrenceIdProperty()?.date?.normalize(deviceZone),
                    isOverride = true,
                    deviceZone,
                )
        }
        return result
            .filter { it.overlaps(from, to) }
            .distinctBy { it.start to it.originalStart }
            .sortedBy { it.start }
            .take(limit)
    }

    /**
     * Applies [draft] to the master event. If the start or the repeat rule changes, the overrides
     * and the EXDATE/RDATE lists no longer match the series, so they are removed.
     */
    fun edit(draft: EventDraft, now: Instant): EventResource {
        val copy = parse(toIcs())
        val event = copy.master ?: throw IllegalStateException("No master event to edit")
        val previous = copy.info()
        val seriesChanged =
            previous == null || previous.start != draft.start || previous.rrule != draft.rrule
        event.applyDraft(draft, previous)
        if (seriesChanged) {
            event.removeAll<VEvent>(Property.EXDATE, Property.RDATE)
            copy.overrides.forEach { copy.calendar.remove<Calendar>(it) }
        }
        event.touch(now, bumpSequence = true)
        copy.addMissingTimeZones()
        return copy
    }

    /**
     * Sets the category and its color on the master event, for example after the user changed the
     * color of a category. Everything else stays as it is.
     */
    fun withCategory(category: String?, color: String?, now: Instant): EventResource {
        val copy = parse(toIcs())
        val event = copy.master ?: throw IllegalStateException("No master event")
        event.setCategory(category, color)
        event.touch(now, bumpSequence = false)
        return copy
    }

    /**
     * Removes one occurrence of a repeating event: adds an EXDATE for [originalStart] and drops the
     * matching override, if any.
     */
    fun excludeOccurrence(originalStart: Instant, deviceZone: ZoneId, now: Instant): EventResource {
        val copy = parse(toIcs())
        val event = copy.master ?: throw IllegalStateException("No master event")
        val dtStart = event.dtStart() ?: throw IllegalStateException("No DTSTART")
        val start = originalStart.toJavaInstant()
        val excluded: IcalTime =
            when (val value = dtStart.date) {
                is LocalDate -> IcalTime.Date(start.atZone(ZoneOffset.UTC).toLocalDate().toKt())
                is ZonedDateTime ->
                    dtStart.tzId()?.let {
                        IcalTime.Zoned(start.atZone(value.zone).toLocalDateTime().toKt(), it)
                    } ?: IcalTime.Utc(originalStart)
                is LocalDateTime ->
                    IcalTime.Floating(start.atZone(deviceZone).toLocalDateTime().toKt())
                else -> IcalTime.Utc(originalStart)
            }
        event.add<VEvent>(ExDate<Temporal>(excluded.parameters(), excluded.value()))
        copy.overrides
            .filter { it.recurrenceIdProperty()?.date?.normalize(deviceZone)?.key() == start }
            .forEach { copy.calendar.remove<Calendar>(it) }
        event.touch(now, bumpSequence = true)
        copy.addMissingTimeZones()
        return copy
    }

    /** Serializes the resource to iCalendar text with CRLF line endings. */
    fun toIcs(): String {
        val writer = StringWriter()
        CalendarOutputter(false).output(calendar, writer)
        return writer.toString()
    }

    /** Adds a compact VTIMEZONE for every system TZID that the events use but do not define. */
    private fun addMissingTimeZones() {
        val defined =
            calendar
                .getComponents<VTimeZone>(Component.VTIMEZONE)
                .mapNotNull { it.getProperty<Property>(Property.TZID).orElse(null)?.value }
                .toSet()
        val used =
            events
                .flatMap { it.getProperties<Property>() }
                .mapNotNull { it.getParameter<TzId>(Parameter.TZID).orElse(null)?.value }
                .toSet()
        for (tzId in used - defined) {
            val zone =
                try {
                    ZoneId.of(tzId)
                } catch (_: Exception) {
                    continue
                }
            calendar.add<Calendar>(parseTimeZone(vTimeZoneText(zone)))
        }
    }

    companion object {
        /** No series is expanded beyond this many occurrences in one call. */
        const val MAX_OCCURRENCES = 1000

        private const val PRODUCT_ID = "-//Grit//Calendar//EN"

        /** Parses one CalDAV resource. Throws if the text is not iCalendar. */
        fun parse(ics: String): EventResource =
            EventResource(CalendarBuilder().build(StringReader(ics)))

        /**
         * Splits an iCalendar file, for example the export of another calendar app, into one
         * resource per UID: a series stays together with its changed occurrences. An event without
         * UID gets a new one. To-dos and other components are left out, and so are calendar
         * properties such as METHOD. Each resource keeps the time zones that its events use.
         */
        fun splitFile(ics: String): List<EventResource> {
            val source = CalendarBuilder().build(StringReader(ics))
            val zones =
                source.getComponents<VTimeZone>(Component.VTIMEZONE).associateBy {
                    it.getProperty<Property>(Property.TZID).orElse(null)?.value
                }
            val groups = LinkedHashMap<String, MutableList<VEvent>>()
            for (event in source.getComponents<VEvent>(Component.VEVENT)) {
                val uid = event.text(Property.UID)?.takeIf { it.isNotBlank() }
                if (uid == null) {
                    groups[UUID.randomUUID().toString()] = mutableListOf(event)
                } else {
                    groups.getOrPut(uid) { mutableListOf() } += event
                }
            }
            return groups.map { (uid, events) ->
                val used =
                    events
                        .flatMap { it.getProperties<Property>() }
                        .mapNotNull { it.getParameter<TzId>(Parameter.TZID).orElse(null)?.value }
                        .toSet()
                val text = buildString {
                    append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:$PRODUCT_ID\r\n")
                    for (tzId in used) zones[tzId]?.let { append(it.toString()) }
                    for (event in events) {
                        val eventText = event.toString()
                        // The generated UID goes right after BEGIN:VEVENT
                        if (event.text(Property.UID).isNullOrBlank()) {
                            append(
                                eventText.replaceFirst(
                                    "BEGIN:VEVENT\r\n",
                                    "BEGIN:VEVENT\r\nUID:$uid\r\n",
                                )
                            )
                        } else {
                            append(eventText)
                        }
                    }
                    append("END:VCALENDAR\r\n")
                }
                parse(text).also { it.addMissingTimeZones() }
            }
        }

        /** Creates a new event resource. [uid] must not contain line breaks. */
        fun create(uid: String, draft: EventDraft, now: Instant): EventResource {
            val stamp = IcalTime.Utc(now).value()
            val ics =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:$PRODUCT_ID\r\n" +
                    "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\nCREATED:$stamp\r\n" +
                    "END:VEVENT\r\nEND:VCALENDAR\r\n"
            val resource = parse(ics)
            val event = resource.master!!
            event.applyDraft(draft, previous = null)
            event.touch(now, bumpSequence = false)
            event.add<VEvent>(Sequence(0))
            resource.addMissingTimeZones()
            return resource
        }

        private fun parseTimeZone(text: String): VTimeZone {
            val wrapper =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:$PRODUCT_ID\r\n${text}END:VCALENDAR\r\n"
            return CalendarBuilder()
                .build(StringReader(wrapper))
                .getComponents<VTimeZone>(Component.VTIMEZONE)
                .first()
        }
    }
}

// --- reading -------------------------------------------------------------------------------

private fun VEvent.text(name: String): String? =
    getProperty<Property>(name).orElse(null)?.value?.takeIf { it.isNotEmpty() }

private fun VEvent.dtStart(): DtStart<Temporal>? =
    getProperty<DtStart<Temporal>>(Property.DTSTART).orElse(null)

private fun VEvent.recurrenceIdProperty(): RecurrenceId<Temporal>? =
    getProperty<RecurrenceId<Temporal>>(Property.RECURRENCE_ID).orElse(null)

private fun DateProperty<*>.tzId(): String? = getParameter<TzId>(Parameter.TZID).orElse(null)?.value

/** The first category. Grit gives an event at most one; other clients may set several. */
private fun VEvent.category(): String? =
    getProperty<Categories>(Property.CATEGORIES).orElse(null)?.categories?.texts?.firstOrNull {
        it.isNotBlank()
    }

private fun VEvent.setCategory(category: String?, color: String?) {
    removeAll<VEvent>(Property.CATEGORIES, Color.PROPERTY_NAME)
    if (category != null) add<VEvent>(Categories(TextList(listOf(category))))
    if (color != null) add<VEvent>(Color(ParameterList(), color))
}

private fun VEvent.isCancelled(): Boolean =
    text(Property.STATUS).equals("CANCELLED", ignoreCase = true)

private fun VEvent.isRecurring(): Boolean =
    getProperties<Property>(Property.RRULE, Property.RDATE).isNotEmpty()

/** DTEND, or DTSTART + DURATION, or the RFC 5545 default (one day, or zero length). */
private fun VEvent.endTime(dtStart: DtStart<Temporal>): IcalTime {
    val dtEnd = getProperty<DtEnd<Temporal>>(Property.DTEND).orElse(null)
    if (dtEnd != null) return dtEnd.date.toIcalTime(dtEnd.tzId())
    val duration = getProperty<DurationProperty>(Property.DURATION).orElse(null)?.duration
    val start = dtStart.date
    val end =
        when {
            duration != null -> start.plusAmount(duration)
            start is LocalDate -> start.plusDays(1)
            else -> start
        }
    return end.toIcalTime(dtStart.tzId())
}

/** Length of one occurrence, measured on the normalized [start]. */
private fun VEvent.length(start: Temporal, deviceZone: ZoneId): TemporalAmount {
    val dtEnd = getProperty<DtEnd<Temporal>>(Property.DTEND).orElse(null)
    if (dtEnd != null) {
        val end = dtEnd.date.normalize(deviceZone)
        return if (start is LocalDate && end is LocalDate) {
            Period.between(start, end).takeIf { !it.isNegative && !it.isZero } ?: Period.ofDays(1)
        } else {
            Duration.between(start.key(), end.key()).takeIf { !it.isNegative } ?: Duration.ZERO
        }
    }
    val duration = getProperty<DurationProperty>(Property.DURATION).orElse(null)?.duration
    if (duration != null) return duration
    return if (start is LocalDate) Period.ofDays(1) else Duration.ZERO
}

/** Display reminders relative to the start, as (alarm, minutes before start). */
private fun VEvent.simpleReminders(): List<Pair<VAlarm, Int>> =
    getComponents<VAlarm>(Component.VALARM).mapNotNull { alarm ->
        val action = alarm.getProperty<Action>(Property.ACTION).orElse(null)?.value?.uppercase()
        if (action != null && action != "DISPLAY" && action != "AUDIO") return@mapNotNull null
        val trigger =
            alarm.getProperty<Trigger>(Property.TRIGGER).orElse(null) ?: return@mapNotNull null
        if (trigger.isAbsolute || trigger.isRelatedToEnd()) return@mapNotNull null
        val minutes = -(trigger.duration?.toMinutes() ?: return@mapNotNull null)
        if (minutes < 0) null else alarm to minutes.toInt()
    }

private fun Trigger.isRelatedToEnd(): Boolean =
    getParameter<Related>(Parameter.RELATED).orElse(null)?.value.equals("END", ignoreCase = true)

private fun TemporalAmount.toMinutes(): Long =
    when (this) {
        is Duration -> toMinutes()
        is Period -> (years * 365L + months * 30L + days) * 24 * 60
        else -> 0
    }

// --- expansion -----------------------------------------------------------------------------

/**
 * Converts a date value to a type that recurrence rules can work with: all-day dates stay
 * `LocalDate`, everything else becomes `ZonedDateTime`. Floating times use [deviceZone].
 */
private fun Temporal.normalize(deviceZone: ZoneId): Temporal =
    when (this) {
        is LocalDate -> this
        is ZonedDateTime -> this
        is LocalDateTime -> atZone(deviceZone)
        is OffsetDateTime -> toZonedDateTime()
        is java.time.Instant -> atZone(ZoneOffset.UTC)
        else -> throw IllegalArgumentException("Unsupported date value: $this")
    }

/** A comparable instant for a normalized value. All-day dates use midnight UTC. */
private fun Temporal.key(): java.time.Instant =
    when (this) {
        is LocalDate -> atStartOfDay(ZoneOffset.UTC).toInstant()
        is ZonedDateTime -> toInstant()
        else -> throw IllegalArgumentException("Not normalized: $this")
    }

private fun Temporal.plusAmount(amount: TemporalAmount): Temporal =
    if (this is LocalDate && amount is Duration) plusDays(maxOf(1, amount.toDays()))
    else plus(amount)

/** Start times of the occurrences of the master event that can overlap [from, to). */
private fun VEvent.startTimes(
    start: Temporal,
    length: TemporalAmount,
    from: Instant,
    to: Instant,
    deviceZone: ZoneId,
    limit: Int,
): List<Temporal> {
    val rrule = getProperty<RRule<Temporal>>(Property.RRULE).orElse(null)
    val rdates =
        getProperties<RDate<Temporal>>(Property.RDATE)
            .flatMap { it.dates.orEmpty() }
            .map { it.normalize(deviceZone) }
    if (rrule == null && rdates.isEmpty()) return listOf(start)

    // An occurrence that starts before `from` still overlaps it if it is long enough
    val span = Duration.between(start.key(), start.plusAmount(length).key()).plusDays(1)
    val windowStart = from.toJavaInstant().minus(span)
    val windowEnd = to.toJavaInstant()
    val periodStart: Temporal
    val periodEnd: Temporal
    if (start is LocalDate) {
        periodStart = windowStart.atZone(ZoneOffset.UTC).toLocalDate()
        periodEnd = windowEnd.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
    } else {
        val zone = (start as ZonedDateTime).zone
        periodStart = windowStart.atZone(zone)
        periodEnd = windowEnd.atZone(zone)
    }

    val times = TreeMap<java.time.Instant, Temporal>()
    fun addIfInWindow(time: Temporal) {
        val key = time.key()
        if (key >= windowStart && key < windowEnd) times[key] = time
    }
    if (rrule != null) {
        rrule.recur.getDates(start, periodStart, periodEnd, limit).forEach {
            addIfInWindow(it.normalize(deviceZone))
        }
    }
    // DTSTART is always the first occurrence, even if it does not match the rule
    addIfInWindow(start)
    rdates.forEach(::addIfInWindow)

    val excluded =
        getProperties<ExDate<Temporal>>(Property.EXDATE)
            .flatMap { it.dates.orEmpty() }
            .map { it.normalize(deviceZone).key() }
            .toSet()
    return times.filterKeys { it !in excluded }.values.toList()
}

private fun VEvent.occurrence(
    start: Temporal,
    end: Temporal,
    originalStart: Temporal?,
    isOverride: Boolean,
    deviceZone: ZoneId,
): Occurrence =
    Occurrence(
        start = start.key().toKotlinInstant(),
        end = end.key().toKotlinInstant(),
        allDay = start is LocalDate,
        originalStart = originalStart?.key()?.toKotlinInstant(),
        isOverride = isOverride,
        summary = text(Property.SUMMARY),
        location = text(Property.LOCATION),
        alarms = alarmTimes(start, end, deviceZone),
    )

/** When the display and audio alarms of an occurrence fire. All-day dates use [deviceZone]. */
private fun VEvent.alarmTimes(start: Temporal, end: Temporal, deviceZone: ZoneId): List<Instant> {
    fun local(time: Temporal): ZonedDateTime =
        if (time is LocalDate) time.atStartOfDay(deviceZone) else time as ZonedDateTime
    return getComponents<VAlarm>(Component.VALARM)
        .mapNotNull { alarm ->
            val action = alarm.getProperty<Action>(Property.ACTION).orElse(null)?.value?.uppercase()
            if (action != null && action != "DISPLAY" && action != "AUDIO") {
                return@mapNotNull null
            }
            val trigger =
                alarm.getProperty<Trigger>(Property.TRIGGER).orElse(null) ?: return@mapNotNull null
            if (trigger.isAbsolute) return@mapNotNull trigger.date.toKotlinInstant()
            val base = if (trigger.isRelatedToEnd()) local(end) else local(start)
            val duration = trigger.duration ?: return@mapNotNull null
            base.plus(duration).toInstant().toKotlinInstant()
        }
        .distinct()
        .sorted()
}

private fun Occurrence.overlaps(from: Instant, to: Instant): Boolean =
    if (start == end) start >= from && start < to else start < to && end > from

// --- writing -------------------------------------------------------------------------------

private fun VEvent.setText(name: String, value: String?, create: (String) -> Property) {
    removeAll<VEvent>(name)
    if (!value.isNullOrBlank()) add<VEvent>(create(value))
}

private fun VEvent.applyDraft(draft: EventDraft, previous: EventInfo?) {
    setText(Property.SUMMARY, draft.summary) { Summary(it) }
    setText(Property.LOCATION, draft.location) { Location(it) }
    setText(Property.DESCRIPTION, draft.description) { Description(it) }

    if (previous?.start != draft.start) {
        removeAll<VEvent>(Property.DTSTART)
        add<VEvent>(DtStart<Temporal>(draft.start.parameters(), draft.start.value()))
    }
    if (previous?.end != draft.end || previous.start != draft.start) {
        removeAll<VEvent>(Property.DTEND, Property.DURATION)
        add<VEvent>(DtEnd<Temporal>(draft.end.parameters(), draft.end.value()))
    }
    if (previous?.rrule != draft.rrule) {
        removeAll<VEvent>(Property.RRULE)
        if (draft.rrule != null) add<VEvent>(RRule<Temporal>(draft.rrule))
    }
    if (previous?.category != draft.category || previous?.color != draft.color) {
        setCategory(draft.category, draft.color)
    }
    if (previous?.reminderMinutes != draft.reminderMinutes.sorted()) {
        simpleReminders().forEach { (alarm, _) -> remove<VEvent>(alarm) }
        for (minutes in draft.reminderMinutes.distinct().sorted()) {
            val alarm = VAlarm(Duration.ofMinutes(-minutes.toLong()))
            alarm.add<VAlarm>(Action("DISPLAY"))
            alarm.add<VAlarm>(Description(draft.summary.ifBlank { "Reminder" }))
            add<VEvent>(alarm)
        }
    }
}

/** Updates DTSTAMP and LAST-MODIFIED, and increments SEQUENCE. */
private fun VEvent.touch(now: Instant, bumpSequence: Boolean) {
    val instant = now.toJavaInstant()
    replace<VEvent>(DtStamp(instant))
    replace<VEvent>(LastModified(instant))
    if (bumpSequence) {
        val sequence = getProperty<Sequence>(Property.SEQUENCE).map { it.sequenceNo }.orElse(0)
        replace<VEvent>(Sequence(sequence + 1))
    }
}

private fun LocalDate.toKt() = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)

private fun LocalDateTime.toKt() =
    kotlinx.datetime.LocalDateTime(year, monthValue, dayOfMonth, hour, minute, second, nano)
