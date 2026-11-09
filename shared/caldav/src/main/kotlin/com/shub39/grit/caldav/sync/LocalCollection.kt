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
package com.shub39.grit.caldav.sync

import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.RemoteItem

/** A local item with changes that are not on the server yet. */
data class LocalItem(
    val id: Long,
    /** Absolute URL where the item lives on the server, or will live after upload. */
    val href: String,
    /** Last known ETag, or null if unknown. */
    val etag: String?,
    /** Current local iCalendar data. Unused for deletions. */
    val ics: String,
    /** Increased on every local change, so that a change made during an upload is not lost. */
    val version: Long,
    val deleted: Boolean,
    /** False for an item that was created locally and never uploaded. */
    val existsOnServer: Boolean,
)

/** Sync state of a collection, stored locally. */
data class SyncState(val syncToken: String?, val ctag: String?)

/**
 * The local side of one synced collection. The calendar stores events; a later task-list store can
 * implement the same interface for VTODO.
 */
interface LocalCollection {
    val url: String
    val type: ComponentType

    /** True if the user may not write to this collection. Local changes are then discarded. */
    suspend fun isReadOnly(): Boolean

    suspend fun syncState(): SyncState

    suspend fun saveSyncState(state: SyncState)

    /** Items that were created, changed or deleted locally. */
    suspend fun pendingChanges(): List<LocalItem>

    /** ETags of the items known to exist on the server, by href. */
    suspend fun knownEtags(): Map<String, String?>

    /**
     * Records a successful upload. Clears the pending flag only if the item still has [version].
     */
    suspend fun markUploaded(id: Long, version: Long, etag: String?)

    /** Removes a locally deleted item after the server deleted it too. */
    suspend fun purge(id: Long)

    /** Stores items from the server. Items with pending local changes are left alone. */
    suspend fun applyRemote(items: List<RemoteItem>)

    /** Removes items that the server deleted. Items with pending local changes are left alone. */
    suspend fun removeRemote(hrefs: Collection<String>)

    /**
     * Discards the local change of item [id] and replaces it with [remote], or removes the item if
     * [remote] is null (it no longer exists on the server).
     */
    suspend fun acceptRemote(id: Long, remote: RemoteItem?)

    /** Keeps the losing side of a conflict, so that it can be shown or recovered later. */
    suspend fun recordConflict(item: LocalItem, remoteIcs: String?)

    suspend fun setReadOnly(readOnly: Boolean)
}
