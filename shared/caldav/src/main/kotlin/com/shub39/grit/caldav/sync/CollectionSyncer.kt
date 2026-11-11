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

import com.shub39.grit.caldav.DavClient
import com.shub39.grit.caldav.InvalidSyncTokenException
import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.caldav.WriteCondition
import com.shub39.grit.caldav.WriteResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Decides which side wins when an item changed both locally and on the server. */
fun interface ConflictPolicy {
    fun localWins(localIcs: String, remoteIcs: String): Boolean

    companion object {
        private val LAST_MODIFIED = Regex("""(?m)^LAST-MODIFIED:(\d{8}T\d{6}Z)\r?$""")

        /**
         * The side with the newer LAST-MODIFIED wins. If the server copy has none, or if both are
         * equal, the server wins: losing a local edit is better than overwriting someone else's
         * change.
         */
        val LastModifiedWins = ConflictPolicy { local, remote ->
            val localTime = LAST_MODIFIED.findAll(local).maxOfOrNull { it.groupValues[1] }
            val remoteTime = LAST_MODIFIED.findAll(remote).maxOfOrNull { it.groupValues[1] }
            localTime != null && remoteTime != null && localTime > remoteTime
        }
    }
}

/** What one sync of a collection did. */
data class SyncReport(
    val uploaded: Int = 0,
    val deletedRemotely: Int = 0,
    val downloaded: Int = 0,
    val removedLocally: Int = 0,
    val conflicts: Int = 0,
    val errors: List<String> = emptyList(),
    /** True if the server reported that the user may no longer write to the collection. */
    val becameReadOnly: Boolean = false,
)

/**
 * Two-way sync of one collection: uploads local changes first, then downloads the server changes.
 * Writes use ETag preconditions, so that nobody overwrites a change they have not seen.
 */
class CollectionSyncer(
    private val dav: DavClient,
    private val policy: ConflictPolicy = ConflictPolicy.LastModifiedWins,
    private val batchSize: Int = 50,
) {
    suspend fun sync(local: LocalCollection): SyncReport =
        lockFor(local.url).withLock {
            var report = SyncReport()
            report = upload(local, report)
            report = download(local, report)
            report
        }

    private suspend fun upload(local: LocalCollection, start: SyncReport): SyncReport {
        var report = start
        val pending = local.pendingChanges()
        if (pending.isEmpty()) return report
        if (local.isReadOnly()) {
            // Edits should not be possible in the UI. If one slipped through, the server wins.
            for (item in pending) local.acceptRemote(item.id, fetch(local, item.href))
            return report
        }
        for (item in pending) {
            report =
                when {
                    item.deleted && !item.existsOnServer -> {
                        local.purge(item.id)
                        report
                    }
                    item.deleted -> uploadDeletion(local, item, report)
                    else -> uploadChange(local, item, report)
                }
            if (report.becameReadOnly) break
        }
        return report
    }

    private suspend fun uploadDeletion(
        local: LocalCollection,
        item: LocalItem,
        report: SyncReport,
    ): SyncReport =
        when (val result = dav.delete(item.href, item.condition())) {
            is WriteResult.Ok,
            WriteResult.NotFound -> {
                local.purge(item.id)
                report.copy(deletedRemotely = report.deletedRemotely + 1)
            }
            // Someone changed the item after we last saw it: keep their version
            WriteResult.Precondition -> {
                val remote = fetch(local, item.href)
                local.recordConflict(item, remote?.ics)
                local.acceptRemote(item.id, remote)
                report.copy(conflicts = report.conflicts + 1)
            }
            WriteResult.Forbidden -> forbidden(local, item, report)
            is WriteResult.Failed -> report.withError("DELETE ${item.href}: ${result.describe()}")
        }

    private suspend fun uploadChange(
        local: LocalCollection,
        item: LocalItem,
        report: SyncReport,
    ): SyncReport {
        var result = dav.put(item.href, item.ics, item.condition())
        if (result == WriteResult.NotFound && item.existsOnServer) {
            // Deleted on the server while we edited it: the edit brings it back
            result = dav.put(item.href, item.ics, WriteCondition.MustNotExist)
        }
        return when (result) {
            is WriteResult.Ok -> {
                local.markUploaded(item.id, item.version, result.etag)
                report.copy(uploaded = report.uploaded + 1)
            }
            WriteResult.Precondition -> resolveConflict(local, item, report)
            WriteResult.Forbidden -> forbidden(local, item, report)
            WriteResult.NotFound -> report.withError("PUT ${item.href}: not found")
            is WriteResult.Failed -> report.withError("PUT ${item.href}: ${result.describe()}")
        }
    }

    /** The server copy changed since our last download. One side wins, the other is kept. */
    private suspend fun resolveConflict(
        local: LocalCollection,
        item: LocalItem,
        report: SyncReport,
    ): SyncReport {
        val remote = fetch(local, item.href)
        val remoteIcs = remote?.ics
        if (remote == null || remoteIcs == null) {
            return report.withError("PUT ${item.href}: conflict, but the server copy is unreadable")
        }
        val counted = report.copy(conflicts = report.conflicts + 1)
        if (policy.localWins(item.ics, remoteIcs) && remote.etag != null) {
            local.recordConflict(item, remoteIcs)
            return when (
                val retry = dav.put(item.href, item.ics, WriteCondition.IfMatch(remote.etag))
            ) {
                is WriteResult.Ok -> {
                    local.markUploaded(item.id, item.version, retry.etag)
                    counted.copy(uploaded = counted.uploaded + 1)
                }
                else -> counted.withError("PUT ${item.href}: conflict retry failed")
            }
        }
        local.recordConflict(item, remoteIcs)
        local.acceptRemote(item.id, remote)
        return counted
    }

    /** A write was refused. If the user lost write access, the whole collection is read-only. */
    private suspend fun forbidden(
        local: LocalCollection,
        item: LocalItem,
        report: SyncReport,
    ): SyncReport {
        val canWrite = dav.collection(local.url)?.canWrite ?: true
        if (canWrite) return report.withError("${item.href}: forbidden")
        local.setReadOnly(true)
        for (pending in local.pendingChanges()) {
            local.acceptRemote(pending.id, fetch(local, pending.href))
        }
        return report.copy(becameReadOnly = true)
    }

    private suspend fun download(local: LocalCollection, start: SyncReport): SyncReport {
        var report = start
        val state = local.syncState()
        val known = local.knownEtags()

        val changed = mutableListOf<RemoteItem>()
        val removed = mutableSetOf<String>()
        var token: String? = null
        var incremental = true
        try {
            var current = state.syncToken
            do {
                val delta = dav.syncCollection(local.url, current)
                changed += delta.changed
                removed += delta.removed
                token = delta.token
                current = delta.token
            } while (delta.truncated && delta.token != null)
        } catch (_: InvalidSyncTokenException) {
            incremental = false
        }
        // Without a token the server lists every item: what it does not list is gone
        if (incremental && state.syncToken == null) {
            removed += known.keys - changed.map { it.href }.toSet()
        }

        if (!incremental) {
            changed.clear()
            removed.clear()
            val remote = dav.listEtags(local.url, local.type)
            changed += remote
            removed += known.keys - remote.map { it.href }.toSet()
            token = dav.collection(local.url)?.syncToken
        }

        val toFetch =
            changed.filter { it.etag == null || known[it.href] != it.etag }.map { it.href }
        for (batch in toFetch.distinct().chunked(batchSize)) {
            val items = dav.multiget(local.url, batch)
            local.applyRemote(items)
            report = report.copy(downloaded = report.downloaded + items.size)
        }
        val toRemove = removed.filter { it in known }
        if (toRemove.isNotEmpty()) {
            local.removeRemote(toRemove)
            report = report.copy(removedLocally = report.removedLocally + toRemove.size)
        }
        local.saveSyncState(SyncState(token, state.ctag))
        return report
    }

    private suspend fun fetch(local: LocalCollection, href: String): RemoteItem? =
        dav.multiget(local.url, listOf(href)).firstOrNull()

    private fun LocalItem.condition(): WriteCondition =
        when {
            !existsOnServer -> WriteCondition.MustNotExist
            etag != null -> WriteCondition.IfMatch(etag)
            else -> WriteCondition.None
        }

    private fun SyncReport.withError(message: String) = copy(errors = errors + message)

    private fun WriteResult.Failed.describe() = "HTTP ${code ?: "?"} ${message.orEmpty()}".trim()

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()

        fun lockFor(url: String): Mutex = locks.getOrPut(url) { Mutex() }
    }
}
