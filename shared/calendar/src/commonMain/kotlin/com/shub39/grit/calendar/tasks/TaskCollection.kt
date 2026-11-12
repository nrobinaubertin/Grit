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

import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.ical.TodoDraft
import com.shub39.grit.caldav.ical.TodoResource
import com.shub39.grit.caldav.sync.LocalCollection
import com.shub39.grit.caldav.sync.LocalItem
import com.shub39.grit.caldav.sync.SyncState
import com.shub39.grit.calendar.data.db.TaskItemEntity
import com.shub39.grit.calendar.data.db.TaskListDao
import com.shub39.grit.calendar.data.db.TaskListEntity
import java.time.ZoneId
import kotlin.time.Instant

/**
 * The local side of one synced task list, for the generic sync engine. The rows hold the server
 * state; the upload text is made from the Grit task at upload time, on top of the last server text,
 * so that properties of other task apps stay.
 */
internal class TaskCollection(
    private val owner: TaskListSync,
    private val dao: TaskListDao,
    private val list: TaskListEntity,
    private val zone: ZoneId,
) : LocalCollection {
    override val url: String = list.url
    override val type = ComponentType.VTODO

    /** The text of each upload, by row id, so that the row keeps what the server got. */
    private val uploads = HashMap<Long, String>()

    override suspend fun isReadOnly(): Boolean = dao.list(list.id)?.canWrite != true

    override suspend fun syncState(): SyncState = SyncState(dao.list(list.id)?.syncToken, null)

    override suspend fun saveSyncState(state: SyncState) =
        dao.setSyncToken(list.id, state.syncToken)

    override suspend fun pendingChanges(): List<LocalItem> {
        val items = dao.items(list.id)
        val result = mutableListOf<LocalItem>()
        for (item in dao.pendingItems(list.id)) {
            if (item.deleted) {
                result += item.toLocalItem(item.ics)
                continue
            }
            // Gone since the last check: the next check marks it deleted
            val draft = owner.draftOf(item, items) ?: continue
            val todo =
                TodoDraft(
                    summary = draft.title,
                    done = draft.done,
                    due = draft.reminder,
                    parentUid = draft.parentUid,
                    sortOrder = draft.index,
                )
            val now = Instant.fromEpochMilliseconds(item.modifiedAt)
            val previous =
                item.ics
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { TodoResource.parse(it) }.getOrNull() }
            val ics =
                if (previous?.info(zone) != null) previous.edit(todo, zone, now).toIcs()
                else TodoResource.create(item.uid, todo, now).toIcs()
            if (item.existsOnServer && ics == item.ics) {
                // The change does not show in the to-do, for example a reminder on a done task
                dao.updateItem(item.copy(dirty = false))
                continue
            }
            uploads[item.id] = ics
            result += item.toLocalItem(ics)
        }
        return result
    }

    private fun TaskItemEntity.toLocalItem(text: String) =
        LocalItem(
            id = id,
            href = href,
            etag = etag,
            ics = text,
            version = version,
            deleted = deleted,
            existsOnServer = existsOnServer,
        )

    override suspend fun knownEtags(): Map<String, String?> =
        dao.knownEtags(list.id).associate { it.href to it.etag }

    override suspend fun markUploaded(id: Long, version: Long, etag: String?) {
        val item = dao.item(id) ?: return
        dao.updateItem(
            item.copy(
                etag = etag,
                ics = uploads[id] ?: item.ics,
                existsOnServer = true,
                // A change made during the upload stays pending
                dirty = item.version != version,
            )
        )
    }

    override suspend fun purge(id: Long) = dao.deleteItem(id)

    override suspend fun applyRemote(items: List<RemoteItem>) {
        for (remote in items) {
            val ics = remote.ics ?: continue
            // A calendar can hold events too: they are not tasks
            if (!TodoResource.isTodo(ics)) continue
            val uid = runCatching { TodoResource.parse(ics).uid }.getOrNull() ?: continue
            val existing = dao.itemByHref(list.id, remote.href)
            when {
                existing == null ->
                    dao.insertItem(
                        TaskItemEntity(
                            listId = list.id,
                            href = remote.href,
                            uid = uid,
                            etag = remote.etag,
                            ics = ics,
                            existsOnServer = true,
                            remoteChanged = true,
                        )
                    )
                // A Grit change waits for upload: it is not overwritten here
                existing.dirty || existing.deleted -> Unit
                else ->
                    dao.updateItem(
                        existing.copy(
                            uid = uid,
                            etag = remote.etag,
                            ics = ics,
                            existsOnServer = true,
                            remoteChanged = true,
                        )
                    )
            }
        }
    }

    override suspend fun removeRemote(hrefs: Collection<String>) {
        for (href in hrefs) {
            val item = dao.itemByHref(list.id, href) ?: continue
            when {
                item.deleted -> dao.deleteItem(item.id)
                // Edited in Grit meanwhile: the upload brings it back
                item.dirty -> dao.updateItem(item.copy(existsOnServer = false, etag = null))
                else -> {
                    owner.removeLocal(item)
                    dao.deleteItem(item.id)
                }
            }
        }
    }

    override suspend fun acceptRemote(id: Long, remote: RemoteItem?) {
        val item = dao.item(id) ?: return
        val ics = remote?.ics
        if (remote == null || ics == null) {
            owner.removeLocal(item)
            dao.deleteItem(item.id)
            return
        }
        dao.updateItem(
            item.copy(
                etag = remote.etag,
                ics = ics,
                dirty = false,
                deleted = false,
                existsOnServer = true,
                remoteChanged = true,
            )
        )
    }

    override suspend fun recordConflict(item: LocalItem, remoteIcs: String?) =
        owner.recordConflict(item.href, item.ics, remoteIcs)

    override suspend fun setReadOnly(readOnly: Boolean) = dao.setCanWrite(list.id, !readOnly)
}
