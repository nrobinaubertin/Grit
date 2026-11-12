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

import com.shub39.grit.caldav.AuthenticationException
import com.shub39.grit.caldav.CollectionInfo
import com.shub39.grit.caldav.ComponentType
import com.shub39.grit.caldav.Credentials
import com.shub39.grit.caldav.Discovery
import com.shub39.grit.caldav.KtorDavClient
import com.shub39.grit.caldav.Sharing
import com.shub39.grit.caldav.WriteCondition
import com.shub39.grit.caldav.WriteResult
import com.shub39.grit.caldav.ical.EventResource
import com.shub39.grit.caldav.sync.CollectionSyncer
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.data.db.KeyValueEntity
import com.shub39.grit.calendar.tasks.TaskListSync
import grit.shared.calendar.generated.resources.Res
import grit.shared.calendar.generated.resources.error_password_missing
import grit.shared.calendar.generated.resources.error_wrong_login
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.koin.core.annotation.Single

/** Accounts, calendar discovery, and sync of all calendars and task lists. */
@Single
class CalendarSyncManager(
    db: CalendarDatabase,
    private val store: CalendarStore,
    private val credentials: CredentialStore,
    private val reminders: ReminderScheduler,
    private val taskLists: TaskListSync,
) {
    private val dao = db.calendarDao()
    private val clock: Clock = Clock.System
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)

    /** True while a sync runs. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Logs in and lists the calendars, without saving anything. */
    suspend fun discover(baseUrl: String, username: String, password: String): Discovery =
        KtorDavClient(baseUrl, Credentials(username, password)).use { it.discover(baseUrl) }

    /** Saves an account and its calendars. Returns the account ID. */
    suspend fun addAccount(
        baseUrl: String,
        username: String,
        password: String,
        discovery: Discovery,
    ): Long {
        val accountId =
            dao.upsertAccount(
                AccountEntity(
                    baseUrl = KtorDavClient.normalizeUrl(baseUrl),
                    username = username,
                    principalUrl = discovery.principalUrl,
                    homeSetUrl = discovery.homeSetUrl,
                )
            )
        credentials.save(accountId, password)
        dao.account(accountId)?.let { reconnectDetached(it, discovery.collections) }
        mergeCollections(accountId, discovery.collections)
        taskLists.merge(accountId, discovery.collections)
        return accountId
    }

    suspend fun updatePassword(accountId: Long, password: String) {
        credentials.save(accountId, password)
    }

    /**
     * Removes the account and its password. Its calendars stay on this device as calendars that do
     * not sync, and its task lists stay linked to their categories without syncing. If an account
     * finds the same calendars or lists later (same paths, even at another server address), they
     * connect again: what changed on this device uploads, the rest takes the server version.
     */
    suspend fun removeAccount(accountId: Long) {
        mutex.withLock {
            for (calendar in dao.collections(accountId)) {
                // Changes that did not reach the server upload on reconnection: everything edited
                // since the last sync, or since the oldest change that still waits for upload
                val oldestPending =
                    dao.pendingEvents(calendar.id)
                        .filter { !it.deleted }
                        .mapNotNull {
                            runCatching { EventResource.parse(it.ics).info()?.lastModified }
                                .getOrNull()
                                ?.toEpochMilliseconds()
                        }
                        .minOrNull()
                val changedSince = listOfNotNull(calendar.lastSync, oldestPending).minOrNull() ?: 1L
                keepOnDevice(
                    calendar,
                    keptServerCopy = true,
                    changedSince = changedSince.coerceAtLeast(1L),
                )
                dao.putValue(KeyValueEntity(DETACHED + calendar.id, 1))
            }
            // The task lists lose their account but keep their categories and sync data
            dao.deleteAccount(accountId)
            credentials.delete(accountId)
        }
        reminders.reschedule()
    }

    /**
     * Turns a server calendar into a calendar on this device. If [keptServerCopy], the events keep
     * their server ETags and Grit remembers the server calendar: discovery skips it, and "Start
     * syncing" or a new account reconnect it. Events edited at or after [changedSince] (epoch
     * milliseconds) count as changed on this device at reconnection.
     */
    private suspend fun keepOnDevice(
        calendar: CollectionEntity,
        keptServerCopy: Boolean,
        changedSince: Long,
    ) {
        val local =
            dao.localAccount()?.id
                ?: dao.upsertAccount(AccountEntity(baseUrl = "", username = "", local = true))
        if (keptServerCopy) {
            dao.putValue(KeyValueEntity(IGNORED + calendar.url.trimEnd('/'), calendar.id))
            dao.putValue(KeyValueEntity(STOPPED_AT + calendar.id, changedSince))
        }
        val url = CalendarStore.LOCAL_URL_PREFIX + UUID.randomUUID() + "/"
        dao.updateCollection(
            calendar.copy(
                accountId = local,
                url = url,
                canWrite = true,
                manual = false,
                syncEnabled = false,
                syncToken = null,
                ctag = null,
                lastSync = null,
                lastError = null,
            )
        )
        // A deletion waiting for upload has nothing left to do
        for (event in dao.pendingEvents(calendar.id)) if (event.deleted) dao.deleteEvent(event.id)
        for (event in dao.eventsOf(calendar.id)) {
            dao.updateEvent(
                event.copy(
                    href = url + event.href.substringAfterLast('/'),
                    dirty = false,
                    // A kept server copy keeps its ETags, for a later reconnection
                    etag = if (keptServerCopy) event.etag else null,
                    existsOnServer = keptServerCopy && event.existsOnServer,
                    syncError = null,
                )
            )
        }
    }

    /**
     * Connects the calendars of a removed account again, when [found] has a calendar at the same
     * path on the server of [account].
     */
    private suspend fun reconnectDetached(account: AccountEntity, found: List<CollectionInfo>) {
        val home = account.homeSetUrl ?: account.principalUrl ?: account.baseUrl
        for (key in dao.keysWithPrefix(DETACHED)) {
            val id = key.removePrefix(DETACHED).toLongOrNull() ?: continue
            val calendar = dao.collection(id)
            if (calendar == null || dao.isLocalCollection(id) != true) {
                dao.deleteValue(key)
                continue
            }
            val former = formerServerCalendar(id) ?: continue
            val info =
                found.firstOrNull {
                    it.supportsEvents && ServerPaths.path(it.url) == ServerPaths.path(former)
                } ?: continue
            // The former address of the server stays in the ignore list: remove it too
            dao.keysWithValue(IGNORED, id).forEach { dao.deleteValue(it) }
            reconnect(calendar, account, home, info)
            dao.deleteValue(key)
        }
    }

    /** Adds a calendar by its URL, for shares that discovery does not list. */
    suspend fun addCollectionByUrl(accountId: Long, url: String): CollectionEntity? {
        val account = dao.account(accountId) ?: return null
        val password = credentials.load(accountId) ?: throw AuthenticationException("No password")
        val info =
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use {
                it.collection(url)
            } ?: return null
        val existing = dao.collections(accountId).firstOrNull { sameUrl(it.url, info.url) }
        if (existing != null) return existing
        // Adding a calendar by hand undoes an earlier "Stop syncing"
        dao.deleteValue(IGNORED + info.url.trimEnd('/'))
        val id = dao.insertCollection(info.toEntity(accountId, manual = true))
        return dao.collection(id)
    }

    suspend fun removeCollection(collectionId: Long) {
        mutex.withLock { dao.deleteCollection(collectionId) }
        reminders.reschedule()
    }

    suspend fun setVisible(collectionId: Long, visible: Boolean) {
        dao.setVisible(collectionId, visible)
        reminders.reschedule()
    }

    suspend fun setSyncEnabled(collectionId: Long, enabled: Boolean) =
        dao.setSyncEnabled(collectionId, enabled)

    /**
     * Turns a calendar on this device into a calendar on the CalDAV server, then syncs. If the
     * calendar came from a server calendar that "Stop syncing" kept and that still exists, Grit
     * reconnects to it: events changed on this device since then upload, the others take the server
     * version. Otherwise Grit creates a new calendar in the calendar home, with the same name and
     * color, and uploads every event. A server calendar whose sync was paused syncs again.
     */
    suspend fun startSyncing(collectionId: Long) {
        mutex.withLock {
            val calendar = dao.collection(collectionId) ?: return
            if (dao.isLocalCollection(collectionId) != true) {
                dao.setSyncEnabled(collectionId, true)
                return@withLock
            }
            val account =
                dao.accounts().firstOrNull { !it.local }
                    ?: throw StartSyncingException("No CalDAV server is connected")
            val password =
                credentials.load(account.id) ?: throw AuthenticationException("No password")
            val home =
                account.homeSetUrl
                    ?: account.principalUrl
                    ?: throw StartSyncingException("The server did not give a calendar home")
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use { dav ->
                // The server can have a new address since: look at the same path there
                val former =
                    formerServerCalendar(collectionId)?.let {
                        ServerPaths.rebase(it, account.baseUrl)
                    }
                val info = former?.let { dav.collection(it) }?.takeIf { it.supportsEvents }
                if (info != null) {
                    reconnect(calendar, account, home, info)
                } else {
                    val url = home.trimEnd('/') + "/" + UUID.randomUUID() + "/"
                    val result =
                        dav.createCalendar(url, calendar.displayName ?: "Calendar", calendar.color)
                    if (result !is WriteResult.Ok) throw StartSyncingException(result.describe())
                    moveToServer(calendar, account, url)
                }
            }
        }
        syncAll()
    }

    /**
     * Creates a task list on the CalDAV server for the Grit task category [categoryId], named
     * [name], then syncs: the tasks of the category upload.
     */
    suspend fun startSyncingTasks(categoryId: Long, name: String) {
        mutex.withLock {
            val account =
                dao.accounts().firstOrNull { !it.local }
                    ?: throw StartSyncingException("No CalDAV server is connected")
            val password =
                credentials.load(account.id) ?: throw AuthenticationException("No password")
            val home =
                account.homeSetUrl
                    ?: account.principalUrl
                    ?: throw StartSyncingException("The server did not give a calendar home")
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use { dav ->
                val url = home.trimEnd('/') + "/" + UUID.randomUUID() + "/"
                val result = dav.createCalendar(url, name, null, ComponentType.VTODO)
                if (result !is WriteResult.Ok) throw StartSyncingException(result.describe())
                taskLists.addCreated(account.id, url, name, categoryId)
            }
        }
        syncAll()
    }

    /** The server calendar that a "Stop syncing" kept for this calendar, if any. */
    private suspend fun formerServerCalendar(collectionId: Long): String? {
        val stoppedAt = dao.getValue(STOPPED_AT + collectionId) ?: return null
        if (stoppedAt <= 0) return null
        return dao.keysWithValue(IGNORED, collectionId).firstOrNull()?.removePrefix(IGNORED)?.let {
            "$it/"
        }
    }

    private suspend fun moveToServer(
        calendar: CollectionEntity,
        account: AccountEntity,
        url: String,
    ) {
        dao.updateCollection(
            calendar.copy(
                accountId = account.id,
                url = url,
                canWrite = true,
                manual = false,
                syncEnabled = true,
                syncToken = null,
                ctag = null,
                lastSync = null,
                lastError = null,
            )
        )
        for (event in dao.eventsOf(calendar.id)) {
            dao.updateEvent(
                event.copy(
                    href = url + event.href.substringAfterLast('/'),
                    etag = null,
                    dirty = true,
                    existsOnServer = false,
                    version = event.version + 1,
                    syncError = null,
                )
            )
        }
    }

    /**
     * Links the calendar back to its former server calendar. The events kept their server ETags, so
     * changed ones upload with If-Match (a newer server version is a normal conflict), new ones
     * upload as new, and unchanged ones take whatever the server has now, deletions included.
     */
    private suspend fun reconnect(
        calendar: CollectionEntity,
        account: AccountEntity,
        home: String,
        info: CollectionInfo,
    ) {
        val stoppedAt = dao.getValue(STOPPED_AT + calendar.id) ?: 0L
        val url = info.url.trimEnd('/') + "/"
        dao.deleteValue(IGNORED + url.trimEnd('/'))
        dao.deleteValue(STOPPED_AT + calendar.id)
        dao.updateCollection(
            calendar.copy(
                accountId = account.id,
                url = url,
                displayName = info.displayName ?: calendar.displayName,
                color = info.color ?: calendar.color,
                canWrite = info.canWrite,
                // Discovery does not find calendars outside the home: keep them anyway
                manual = !url.startsWith(home.trimEnd('/') + "/"),
                syncEnabled = true,
                syncToken = null,
                ctag = null,
                lastSync = null,
                lastError = null,
            )
        )
        for (event in dao.eventsOf(calendar.id)) {
            val href = url + event.href.substringAfterLast('/')
            // Every edit in Grit writes LAST-MODIFIED: without it, the event was not edited here
            val changed =
                runCatching { EventResource.parse(event.ics).info()?.lastModified }
                    .getOrNull()
                    // LAST-MODIFIED has whole seconds: an edit in the second of the stop counts
                    ?.let { it.toEpochMilliseconds() >= stoppedAt / 1000 * 1000 } ?: false
            val updated =
                when {
                    event.etag == null || !event.existsOnServer ->
                        event.copy(dirty = true, existsOnServer = false)
                    changed -> event.copy(dirty = true, existsOnServer = true)
                    else -> event.copy(dirty = false, existsOnServer = true)
                }
            dao.updateEvent(
                updated.copy(
                    href = href,
                    version = if (updated.dirty) event.version + 1 else event.version,
                    syncError = null,
                )
            )
        }
    }

    /** What "Stop syncing" did with the server copy. */
    enum class StopResult {
        /** The calendar was the user's alone: Grit deleted it on the server. */
        Deleted,

        /** Someone else shares it: it stays on the server. */
        KeptShared,

        /** Grit could not tell who shares it, or could not delete it: it stays on the server. */
        KeptUnknown,
    }

    /**
     * Turns a server calendar into a calendar on this device, with all its events. If the calendar
     * is the user's alone (in their calendar home and in no share), Grit deletes it on the server,
     * so the user's other devices lose it too. Otherwise the server copy stays for the other
     * people, discovery leaves it out, and a later "Start syncing" reconnects to it. Changes that
     * are not uploaded yet stay on this device.
     */
    suspend fun stopSyncing(collectionId: Long): StopResult {
        val result: StopResult
        mutex.withLock {
            val calendar = dao.collection(collectionId) ?: return StopResult.KeptUnknown
            if (dao.isLocalCollection(collectionId) == true) return StopResult.KeptUnknown
            val sharing = sharingOf(calendar)
            val deleted = sharing == Sharing.None && deleteOnServer(calendar)
            result =
                when {
                    deleted -> StopResult.Deleted
                    sharing == Sharing.Shared -> StopResult.KeptShared
                    else -> StopResult.KeptUnknown
                }
            // Remember the server calendar: discovery skips it, "Start syncing" reuses it
            keepOnDevice(
                calendar,
                keptServerCopy = !deleted,
                changedSince = clock.now().toEpochMilliseconds(),
            )
        }
        reminders.reschedule()
        return result
    }

    /** [Sharing.None] only for a calendar in the user's home that appears in no share. */
    private suspend fun sharingOf(calendar: CollectionEntity): Sharing {
        val account = dao.account(calendar.accountId) ?: return Sharing.Unknown
        val home = account.homeSetUrl ?: account.principalUrl ?: return Sharing.Unknown
        // A calendar outside the home belongs to someone else
        if (!calendar.url.startsWith(home.trimEnd('/') + "/")) return Sharing.Shared
        val password = credentials.load(account.id) ?: return Sharing.Unknown
        return try {
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use {
                it.sharing(calendar.url, account.username)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Sharing.Unknown
        }
    }

    private suspend fun deleteOnServer(calendar: CollectionEntity): Boolean {
        val account = dao.account(calendar.accountId) ?: return false
        val password = credentials.load(account.id) ?: return false
        return try {
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use {
                when (it.delete(calendar.url, WriteCondition.None)) {
                    is WriteResult.Ok,
                    WriteResult.NotFound -> true
                    else -> false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Syncs every calendar of every account. Errors are stored on the account and calendar. */
    suspend fun syncAll() {
        mutex.withLock {
            _running.value = true
            try {
                // Calendars on this device have no server
                for (account in dao.accounts()) if (!account.local) syncAccount(account)
                store.moveWindowForward()
            } finally {
                _running.value = false
            }
        }
        reminders.reschedule()
    }

    private suspend fun syncAccount(account: AccountEntity) {
        val now = clock.now().toEpochMilliseconds()
        val password = credentials.load(account.id)
        if (password == null) {
            dao.setAccountSyncResult(account.id, now, getString(Res.string.error_password_missing))
            return
        }
        try {
            KtorDavClient(account.baseUrl, Credentials(account.username, password)).use { dav ->
                // Refresh names, colors and access rights, and find new or removed calendars
                val found = dav.discover(account.baseUrl).collections
                reconnectDetached(account, found)
                mergeCollections(account.id, found)
                taskLists.merge(account.id, found)
                for (manual in dao.collections(account.id).filter { it.manual }) {
                    dav.collection(manual.url)
                        ?.let { refreshed(manual, it) }
                        ?.let { dao.updateCollection(it) }
                }
                val syncer = CollectionSyncer(dav)
                for (collection in dao.collections(account.id)) {
                    if (!collection.syncEnabled || !collection.supportsEvents) continue
                    val result =
                        try {
                            val report =
                                syncer.sync(
                                    EventCollection(store, dao, collection.id, collection.url)
                                )
                            report.errors.firstOrNull()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AuthenticationException) {
                            throw e
                        } catch (e: Exception) {
                            e.message ?: e::class.simpleName
                        }
                    dao.setCollectionSyncResult(
                        collection.id,
                        clock.now().toEpochMilliseconds(),
                        result,
                    )
                }
                taskLists.syncAccount(dav, account.id)
            }
            dao.setAccountSyncResult(account.id, clock.now().toEpochMilliseconds(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: AuthenticationException) {
            dao.setAccountSyncResult(account.id, now, getString(Res.string.error_wrong_login))
        } catch (e: Exception) {
            dao.setAccountSyncResult(account.id, now, e.message ?: e::class.simpleName)
        }
    }

    /**
     * Adds calendars that discovery found and updates the known ones. Calendars that discovery no
     * longer lists are removed, except those added by URL.
     */
    private suspend fun mergeCollections(accountId: Long, found: List<CollectionInfo>) {
        // Calendars whose sync the user stopped stay out
        // By path: the same calendar at another address of the server stays out too
        val ignored =
            dao.keysWithPrefix(IGNORED).map { ServerPaths.path(it.removePrefix(IGNORED)) }.toSet()
        val events = found.filter { it.supportsEvents && ServerPaths.path(it.url) !in ignored }
        val existing = dao.collections(accountId)
        for (info in events) {
            val match = existing.firstOrNull { sameUrl(it.url, info.url) }
            if (match == null) dao.insertCollection(info.toEntity(accountId, manual = false))
            else dao.updateCollection(refreshed(match, info))
        }
        for (collection in existing) {
            if (!collection.manual && events.none { sameUrl(it.url, collection.url) }) {
                dao.deleteCollection(collection.id)
            }
        }
    }

    private fun refreshed(collection: CollectionEntity, info: CollectionInfo) =
        collection.copy(
            displayName = info.displayName,
            color = info.color,
            supportsEvents = info.supportsEvents,
            supportsTasks = info.supportsTasks,
            canWrite = info.canWrite,
        )

    private fun CollectionInfo.toEntity(accountId: Long, manual: Boolean) =
        CollectionEntity(
            accountId = accountId,
            url = url,
            displayName = displayName,
            color = color,
            supportsEvents = supportsEvents,
            supportsTasks = supportsTasks,
            canWrite = canWrite,
            manual = manual,
        )

    private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    private fun WriteResult.describe(): String =
        when (this) {
            is WriteResult.Ok -> "OK"
            WriteResult.Forbidden -> "The server does not allow new calendars here"
            WriteResult.Precondition -> "A calendar with this address exists already"
            WriteResult.NotFound -> "The calendar home was not found"
            is WriteResult.Failed -> "HTTP ${code ?: "?"} ${message.orEmpty()}".trim()
        }

    private companion object {
        /** kv key prefix of the server calendars whose sync the user stopped. */
        const val IGNORED = "ignored:"

        /** kv key prefix: when the user stopped syncing a calendar, by calendar id. */
        const val STOPPED_AT = "stopped-at:"

        /**
         * kv key prefix, by calendar id: a calendar of a removed account. It connects again when an
         * account finds a calendar at the same path.
         */
        const val DETACHED = "detached:"
    }
}

/** Why a calendar could not start syncing. */
class StartSyncingException(message: String) : Exception(message)
