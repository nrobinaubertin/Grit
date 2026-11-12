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

import com.shub39.grit.caldav.CollectionInfo
import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.DavClient
import com.shub39.grit.caldav.Discovery
import com.shub39.grit.caldav.InvalidSyncTokenException
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.Sharing
import com.shub39.grit.caldav.SyncDelta
import com.shub39.grit.caldav.WriteCondition
import com.shub39.grit.caldav.WriteResult

const val COLLECTION = "https://dav.example/alice/family/"

fun ics(uid: String, summary: String, lastModified: String = "20260101T000000Z") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
        "DTSTAMP:20260101T000000Z\r\nLAST-MODIFIED:$lastModified\r\n" +
        "DTSTART:20260301T100000Z\r\nSUMMARY:$summary\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

/** An in-memory CalDAV collection with sync tokens. */
class FakeServer : DavClient {
    val items = linkedMapOf<String, Pair<String, String>>()
    private var version = 0
    private val log = mutableListOf<Pair<Int, String>>()
    var canWrite = true
    var acceptTokens = true
    var beforePut: (String) -> Unit = {}
    val requests = mutableListOf<String>()

    fun serverPut(href: String, ics: String): String {
        version++
        val etag = "\"e$version\""
        items[href] = etag to ics
        log += version to href
        return etag
    }

    fun serverDelete(href: String) {
        version++
        items.remove(href)
        log += version to href
    }

    override suspend fun discover(baseUrl: String) = Discovery(null, null, emptyList())

    override suspend fun collection(url: String) =
        CollectionInfo(url, "family", null, true, false, canWrite, null, "t$version")

    override suspend fun syncCollection(url: String, token: String?): SyncDelta {
        requests += "SYNC $token"
        if (token != null && !acceptTokens) throw InvalidSyncTokenException(null)
        val since = token?.removePrefix("t")?.toInt() ?: 0
        val hrefs = log.filter { it.first > since }.map { it.second }.distinct()
        return SyncDelta(
            changed = hrefs.filter { it in items }.map { RemoteItem(it, items.getValue(it).first) },
            removed = hrefs.filter { it !in items },
            token = "t$version",
            truncated = false,
        )
    }

    override suspend fun listEtags(url: String, type: ComponentType) =
        items.map { (href, item) -> RemoteItem(href, item.first) }

    override suspend fun multiget(url: String, hrefs: List<String>) =
        hrefs.mapNotNull { href -> items[href]?.let { RemoteItem(href, it.first, it.second) } }

    override suspend fun put(href: String, ics: String, condition: WriteCondition): WriteResult {
        requests += "PUT $href $condition"
        beforePut(href)
        if (!canWrite) return WriteResult.Forbidden
        val current = items[href]
        when (condition) {
            WriteCondition.MustNotExist -> if (current != null) return WriteResult.Precondition
            is WriteCondition.IfMatch -> {
                if (current == null) return WriteResult.NotFound
                if (current.first != condition.etag) return WriteResult.Precondition
            }
            WriteCondition.None -> Unit
        }
        return WriteResult.Ok(serverPut(href, ics))
    }

    override suspend fun delete(href: String, condition: WriteCondition): WriteResult {
        requests += "DELETE $href $condition"
        if (!canWrite) return WriteResult.Forbidden
        val current = items[href] ?: return WriteResult.NotFound
        if (condition is WriteCondition.IfMatch && current.first != condition.etag) {
            return WriteResult.Precondition
        }
        serverDelete(href)
        return WriteResult.Ok(null)
    }

    override suspend fun createCalendar(
        url: String,
        displayName: String,
        color: Int?,
        type: ComponentType,
    ): WriteResult {
        requests += "MKCALENDAR $url $displayName"
        return if (canWrite) WriteResult.Ok(null) else WriteResult.Forbidden
    }

    override suspend fun sharing(url: String, username: String): Sharing = Sharing.Unknown
}

/** An in-memory local collection. */
class FakeLocal(override val url: String = COLLECTION) : LocalCollection {
    data class Row(
        val href: String,
        val etag: String?,
        val ics: String,
        val version: Long = 0,
        val dirty: Boolean = false,
        val deleted: Boolean = false,
        val existsOnServer: Boolean = true,
    )

    override val type = ComponentType.VEVENT
    val rows = linkedMapOf<Long, Row>()
    val conflicts = mutableListOf<Pair<String, String?>>()
    var readOnly = false
    var state = SyncState(null, null)
    private var nextId = 1L

    fun byHref(href: String) = rows.values.firstOrNull { it.href == href }

    fun idOf(href: String) = rows.entries.first { it.value.href == href }.key

    fun create(uid: String, ics: String): Long {
        val id = nextId++
        rows[id] = Row("$url$uid.ics", null, ics, 1, dirty = true, existsOnServer = false)
        return id
    }

    fun edit(id: Long, ics: String) {
        val row = rows.getValue(id)
        rows[id] = row.copy(ics = ics, version = row.version + 1, dirty = true)
    }

    fun delete(id: Long) {
        val row = rows.getValue(id)
        rows[id] = row.copy(version = row.version + 1, dirty = true, deleted = true)
    }

    override suspend fun isReadOnly() = readOnly

    override suspend fun syncState() = state

    override suspend fun saveSyncState(state: SyncState) {
        this.state = state
    }

    override suspend fun pendingChanges() =
        rows
            .filter { it.value.dirty }
            .map { (id, r) ->
                LocalItem(id, r.href, r.etag, r.ics, r.version, r.deleted, r.existsOnServer)
            }

    override suspend fun knownEtags() =
        rows.values.filter { it.existsOnServer }.associate { it.href to it.etag }

    override suspend fun markUploaded(id: Long, version: Long, etag: String?) {
        val row = rows.getValue(id)
        rows[id] =
            row.copy(
                etag = etag,
                existsOnServer = true,
                dirty = row.dirty && row.version != version,
            )
    }

    override suspend fun purge(id: Long) {
        rows.remove(id)
    }

    override suspend fun applyRemote(items: List<RemoteItem>) {
        for (item in items) {
            val existing = rows.entries.firstOrNull { it.value.href == item.href }
            if (existing != null) {
                if (existing.value.dirty) continue
                rows[existing.key] = existing.value.copy(etag = item.etag, ics = item.ics!!)
            } else {
                rows[nextId++] = Row(item.href, item.etag, item.ics!!)
            }
        }
    }

    override suspend fun removeRemote(hrefs: Collection<String>) {
        rows.entries.removeIf { it.value.href in hrefs && !it.value.dirty }
    }

    override suspend fun acceptRemote(id: Long, remote: RemoteItem?) {
        if (remote == null) rows.remove(id)
        else rows[id] = Row(remote.href, remote.etag, remote.ics!!)
    }

    override suspend fun recordConflict(item: LocalItem, remoteIcs: String?) {
        conflicts += item.ics to remoteIcs
    }

    override suspend fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
    }
}
