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
package com.shub39.grit.calendar.data

import androidx.room3.withWriteTransaction
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.ical.EventDraft
import com.shub39.grit.caldav.ical.EventInfo
import com.shub39.grit.caldav.ical.EventResource
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.AlarmEntity
import com.shub39.grit.calendar.data.db.CalendarDao
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.data.db.ConflictEntity
import com.shub39.grit.calendar.data.db.EventEntity
import com.shub39.grit.calendar.data.db.InstanceEntity
import com.shub39.grit.calendar.data.db.InstanceRow
import com.shub39.grit.calendar.data.db.KeyValueEntity
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

/** An event with the data that the detail sheet and the editor need. */
data class EventDetails(val eventId: Long, val collection: CollectionEntity, val info: EventInfo)

/**
 * Local storage of synced events. Every change of an event expands it again into instances and
 * alarms. Repeating events are expanded for a window around today; single events always have their
 * one instance.
 */
@Single
class CalendarStore(private val db: CalendarDatabase) {
    /** Replaced in tests. */
    internal var clock: Clock = Clock.System

    private val dao: CalendarDao = db.calendarDao()
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after every local edit, so that a sync can be scheduled. */
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    val zone: ZoneId
        get() = ZoneId.systemDefault()

    fun instances(from: Long, to: Long): Flow<List<InstanceRow>> = dao.instancesFlow(from, to)

    fun collectionsFlow() = dao.collectionsFlow()

    fun accountsFlow() = dao.accountsFlow()

    fun pendingCountFlow() = dao.pendingCountFlow()

    suspend fun details(eventId: Long): EventDetails? {
        val event = dao.event(eventId) ?: return null
        val collection = dao.collection(event.collectionId) ?: return null
        val info = runCatching { EventResource.parse(event.ics).info() }.getOrNull() ?: return null
        return EventDetails(eventId, collection, info)
    }

    // --- calendars on this device ---

    /** Creates a calendar that exists only on this device. Returns its ID. */
    suspend fun createLocalCalendar(name: String): Long {
        val account =
            dao.localAccount()?.id
                ?: dao.upsertAccount(AccountEntity(baseUrl = "", username = "", local = true))
        return dao.insertCollection(
            CollectionEntity(
                accountId = account,
                url = LOCAL_URL_PREFIX + UUID.randomUUID() + "/",
                displayName = name,
                canWrite = true,
                syncEnabled = false,
            )
        )
    }

    /** Deletes a calendar on this device with all its events. */
    suspend fun deleteLocalCalendar(collectionId: Long) {
        if (dao.isLocalCollection(collectionId) != true) return
        dao.deleteCollection(collectionId)
        _localChanges.tryEmit(Unit)
    }

    // --- categories ---

    fun categoriesFlow() = dao.categoriesFlow()

    suspend fun createCategory(name: String, color: String) {
        dao.upsertCategory(CategoryEntity(name.trim(), color))
    }

    /**
     * Changes the color of a category, and writes it into the events of the category so that the
     * people who share these calendars see it too.
     */
    suspend fun setCategoryColor(name: String, color: String) {
        dao.upsertCategory(CategoryEntity(name, color))
        for (event in dao.writableEventsWithCategory(name)) {
            val resource = parseOrNull(event.ics) ?: continue
            saveLocal(event, resource.withCategory(name, color, clock.now()), notify = false)
        }
        _localChanges.tryEmit(Unit)
    }

    /** Deletes a category and removes it from the events that the user may change. */
    suspend fun deleteCategory(name: String) {
        for (event in dao.writableEventsWithCategory(name)) {
            val resource = parseOrNull(event.ics) ?: continue
            saveLocal(event, resource.withCategory(null, null, clock.now()), notify = false)
        }
        dao.deleteCategory(name)
        _localChanges.tryEmit(Unit)
    }

    /** Adds the category of an event from the server, with the event's color if it has one. */
    private suspend fun registerCategory(info: EventInfo?) {
        val name = info?.category ?: return
        val color = CategoryColors.normalize(info.color)
        val existing = dao.category(name)
        if (existing == null) {
            dao.upsertCategory(CategoryEntity(name, color ?: CategoryColors.defaultFor(name)))
        } else if (color != null && color != existing.color) {
            dao.upsertCategory(existing.copy(color = color))
        }
    }

    /** Fills in the category of events stored before categories existed. Runs once. */
    suspend fun indexCategories() {
        if (dao.getValue(CATEGORIES_INDEXED) != null) return
        for (event in dao.eventsWithoutCategory()) {
            val info = parseOrNull(event.ics)?.info() ?: continue
            if (info.category == null) continue
            dao.updateEvent(event.copy(category = info.category))
            registerCategory(info)
        }
        dao.putValue(KeyValueEntity(CATEGORIES_INDEXED, 1))
    }

    // --- expansion window ---

    private suspend fun window(): Pair<Long, Long> {
        val start = dao.getValue(WINDOW_START)
        val end = dao.getValue(WINDOW_END)
        if (start != null && end != null) return start to end
        val now = clock.now()
        val window =
            (now - WINDOW_BEFORE).toEpochMilliseconds() to
                (now + WINDOW_AFTER).toEpochMilliseconds()
        dao.putValue(KeyValueEntity(WINDOW_START, window.first))
        dao.putValue(KeyValueEntity(WINDOW_END, window.second))
        return window
    }

    /** Grows the window so that it covers [from, to), and expands repeating events again. */
    suspend fun ensureWindow(from: Long, to: Long) {
        val (start, end) = window()
        if (from >= start && to <= end) return
        setWindow(minOf(start, from), maxOf(end, to))
    }

    /** Moves the window forward with today. Called once a day and after each sync. */
    suspend fun moveWindowForward() {
        val (start, end) = window()
        val now = clock.now()
        val wantedEnd = (now + WINDOW_AFTER).toEpochMilliseconds()
        if (wantedEnd - end < 1.days.inWholeMilliseconds) return
        setWindow(maxOf(start, (now - WINDOW_BEFORE).toEpochMilliseconds()), wantedEnd)
    }

    /** Expands all events again, for example after a time zone change. */
    suspend fun expandAll() {
        for (id in dao.eventIds()) dao.event(id)?.let { expand(it) }
    }

    private suspend fun setWindow(start: Long, end: Long) {
        dao.putValue(KeyValueEntity(WINDOW_START, start))
        dao.putValue(KeyValueEntity(WINDOW_END, end))
        for (id in dao.recurringEventIds()) dao.event(id)?.let { expand(it) }
    }

    private suspend fun expand(event: EventEntity) {
        db.withWriteTransaction {
            dao.deleteInstances(event.id)
            dao.deleteAlarms(event.id)
            if (event.deleted) return@withWriteTransaction
            val resource = parseOrNull(event.ics) ?: return@withWriteTransaction
            val (from, to) = if (event.recurring) window() else FAR_PAST to FAR_FUTURE
            val occurrences =
                resource.expand(
                    Instant.fromEpochMilliseconds(from),
                    Instant.fromEpochMilliseconds(to),
                    zone,
                )
            dao.insertInstances(
                occurrences.map {
                    InstanceEntity(
                        eventId = event.id,
                        collectionId = event.collectionId,
                        begin = it.start.toEpochMilliseconds(),
                        end = it.end.toEpochMilliseconds(),
                        allDay = it.allDay,
                        originalStart = it.originalStart?.toEpochMilliseconds(),
                        isOverride = it.isOverride,
                        title = it.summary,
                        location = it.location,
                    )
                }
            )
            val oldest = (clock.now() - 1.days).toEpochMilliseconds()
            dao.insertAlarms(
                occurrences.flatMap { occurrence ->
                    occurrence.alarms
                        .map { it.toEpochMilliseconds() }
                        .filter { it > oldest }
                        .map {
                            AlarmEntity(
                                eventId = event.id,
                                triggerAt = it,
                                instanceBegin = occurrence.start.toEpochMilliseconds(),
                                instanceAllDay = occurrence.allDay,
                                title = occurrence.summary,
                                location = occurrence.location,
                            )
                        }
                }
            )
        }
    }

    // --- changes from the server ---

    suspend fun applyRemote(collectionId: Long, items: List<RemoteItem>) {
        for (item in items) {
            val ics = item.ics ?: continue
            val existing = dao.eventByHref(collectionId, item.href)
            if (existing?.dirty == true) continue
            val event = fromServer(collectionId, item.href, item.etag, ics, existing)
            registerCategory(parseOrNull(ics)?.info())
            val id =
                if (existing == null) {
                    dao.insertEvent(event)
                } else {
                    dao.updateEvent(event)
                    event.id
                }
            expand(event.copy(id = id))
        }
    }

    suspend fun removeRemote(collectionId: Long, hrefs: Collection<String>) {
        for (id in dao.cleanEventIds(collectionId, hrefs.toList())) dao.deleteEvent(id)
    }

    suspend fun acceptRemote(eventId: Long, remote: RemoteItem?) {
        val existing = dao.event(eventId) ?: return
        val ics = remote?.ics
        if (remote == null || ics == null) {
            dao.deleteEvent(eventId)
            return
        }
        val event = fromServer(existing.collectionId, remote.href, remote.etag, ics, existing)
        registerCategory(parseOrNull(ics)?.info())
        dao.updateEvent(event)
        expand(event)
    }

    suspend fun markUploaded(eventId: Long, version: Long, etag: String?) {
        if (dao.markUploaded(eventId, version, etag) == 0) dao.setUploadedEtag(eventId, etag)
    }

    suspend fun recordConflict(
        collectionId: Long,
        href: String,
        localIcs: String,
        remoteIcs: String?,
    ) {
        dao.insertConflict(
            ConflictEntity(
                collectionId = collectionId,
                href = href,
                localIcs = localIcs,
                remoteIcs = remoteIcs,
                createdAt = clock.now().toEpochMilliseconds(),
            )
        )
    }

    private fun fromServer(
        collectionId: Long,
        href: String,
        etag: String?,
        ics: String,
        existing: EventEntity?,
    ): EventEntity {
        val info = parseOrNull(ics)?.info()
        val base =
            existing ?: EventEntity(collectionId = collectionId, href = href, uid = "", ics = ics)
        return base.copy(
            href = href,
            uid = info?.uid ?: base.uid.ifEmpty { href },
            etag = etag,
            ics = ics,
            dirty = false,
            deleted = false,
            existsOnServer = true,
            summary = info?.summary,
            location = info?.location,
            description = info?.description,
            recurring = info.isRecurring(),
            syncError = if (info == null) "Unreadable iCalendar data" else null,
            category = info?.category,
        )
    }

    // --- local edits ---

    suspend fun createEvent(collectionId: Long, draft: EventDraft): Long {
        val collection = dao.collection(collectionId) ?: error("Unknown calendar $collectionId")
        val local = dao.isLocalCollection(collectionId) == true
        val uid = UUID.randomUUID().toString()
        val resource = EventResource.create(uid, draft, clock.now())
        val event =
            EventEntity(
                collectionId = collectionId,
                href = collection.url.trimEnd('/') + "/" + uid + ".ics",
                uid = uid,
                ics = resource.toIcs(),
                // Events of a calendar on this device have nothing to upload
                dirty = !local,
                version = 1,
                existsOnServer = false,
                summary = draft.summary,
                location = draft.location,
                description = draft.description,
                recurring = draft.rrule != null,
                category = draft.category,
            )
        val id = dao.insertEvent(event)
        expand(event.copy(id = id))
        _localChanges.tryEmit(Unit)
        return id
    }

    suspend fun updateEvent(eventId: Long, draft: EventDraft) {
        val event = dao.event(eventId) ?: return
        val resource = EventResource.parse(event.ics).edit(draft, clock.now())
        saveLocal(event, resource)
    }

    /** Removes one occurrence of a repeating event. */
    suspend fun deleteOccurrence(eventId: Long, originalStart: Long) {
        val event = dao.event(eventId) ?: return
        val resource =
            EventResource.parse(event.ics)
                .excludeOccurrence(Instant.fromEpochMilliseconds(originalStart), zone, clock.now())
        saveLocal(event, resource)
    }

    suspend fun deleteEvent(eventId: Long) {
        val event = dao.event(eventId) ?: return
        if (!event.existsOnServer || dao.isLocalCollection(event.collectionId) == true) {
            dao.deleteEvent(eventId)
        } else {
            val deleted = event.copy(deleted = true, dirty = true, version = event.version + 1)
            dao.updateEvent(deleted)
            expand(deleted)
        }
        _localChanges.tryEmit(Unit)
    }

    private suspend fun saveLocal(
        event: EventEntity,
        resource: EventResource,
        notify: Boolean = true,
    ) {
        val info = resource.info()
        val updated =
            event.copy(
                ics = resource.toIcs(),
                dirty = dao.isLocalCollection(event.collectionId) != true,
                version = event.version + 1,
                summary = info?.summary,
                location = info?.location,
                description = info?.description,
                recurring = info.isRecurring(),
                syncError = null,
                category = info?.category,
            )
        dao.updateEvent(updated)
        expand(updated)
        if (notify) _localChanges.tryEmit(Unit)
    }

    private fun parseOrNull(ics: String): EventResource? =
        runCatching { EventResource.parse(ics) }.getOrNull()

    private fun EventInfo?.isRecurring(): Boolean = this != null && (rrule != null || hasExceptions)

    companion object {
        /** URL prefix of calendars on this device. They have no server URL. */
        const val LOCAL_URL_PREFIX = "local:"

        private const val CATEGORIES_INDEXED = "categories_indexed"
        private const val WINDOW_START = "window_start"
        private const val WINDOW_END = "window_end"
        private val WINDOW_BEFORE = 92.days
        private val WINDOW_AFTER = 400.days

        /** Bounds used for single events, which are never cut by the window. */
        private val FAR_PAST = Instant.parse("1900-01-01T00:00:00Z").toEpochMilliseconds()
        private val FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z").toEpochMilliseconds()
    }
}
