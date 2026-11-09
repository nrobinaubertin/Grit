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

import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.sync.LocalCollection
import com.shub39.grit.caldav.sync.LocalItem
import com.shub39.grit.caldav.sync.SyncState
import com.shub39.grit.calendar.data.db.CalendarDao

/** The local side of one synced calendar, for the generic sync engine. */
class EventCollection(
    private val store: CalendarStore,
    private val dao: CalendarDao,
    private val collectionId: Long,
    override val url: String,
) : LocalCollection {
    override val type = ComponentType.VEVENT

    override suspend fun isReadOnly(): Boolean = dao.collection(collectionId)?.canWrite != true

    override suspend fun syncState(): SyncState {
        val collection = dao.collection(collectionId)
        return SyncState(collection?.syncToken, collection?.ctag)
    }

    override suspend fun saveSyncState(state: SyncState) =
        dao.setSyncState(collectionId, state.syncToken, state.ctag)

    override suspend fun pendingChanges(): List<LocalItem> =
        dao.pendingEvents(collectionId).map {
            LocalItem(
                id = it.id,
                href = it.href,
                etag = it.etag,
                ics = it.ics,
                version = it.version,
                deleted = it.deleted,
                existsOnServer = it.existsOnServer,
            )
        }

    override suspend fun knownEtags(): Map<String, String?> =
        dao.knownEtags(collectionId).associate { it.href to it.etag }

    override suspend fun markUploaded(id: Long, version: Long, etag: String?) =
        store.markUploaded(id, version, etag)

    override suspend fun purge(id: Long) = dao.deleteEvent(id)

    override suspend fun applyRemote(items: List<RemoteItem>) =
        store.applyRemote(collectionId, items)

    override suspend fun removeRemote(hrefs: Collection<String>) =
        store.removeRemote(collectionId, hrefs)

    override suspend fun acceptRemote(id: Long, remote: RemoteItem?) =
        store.acceptRemote(id, remote)

    override suspend fun recordConflict(item: LocalItem, remoteIcs: String?) =
        store.recordConflict(collectionId, item.href, item.ics, remoteIcs)

    override suspend fun setReadOnly(readOnly: Boolean) = dao.setCanWrite(collectionId, !readOnly)
}
