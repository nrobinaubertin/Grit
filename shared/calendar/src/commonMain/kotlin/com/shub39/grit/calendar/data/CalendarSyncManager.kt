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
import com.shub39.grit.caldav.Credentials
import com.shub39.grit.caldav.Discovery
import com.shub39.grit.caldav.KtorDavClient
import com.shub39.grit.caldav.sync.CollectionSyncer
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CollectionEntity
import grit.shared.calendar.generated.resources.Res
import grit.shared.calendar.generated.resources.error_password_missing
import grit.shared.calendar.generated.resources.error_wrong_login
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.koin.core.annotation.Single

/** Accounts, calendar discovery, and sync of all calendars. */
@Single
class CalendarSyncManager(
    db: CalendarDatabase,
    private val store: CalendarStore,
    private val credentials: CredentialStore,
    private val reminders: ReminderScheduler,
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
        mergeCollections(accountId, discovery.collections)
        return accountId
    }

    suspend fun updatePassword(accountId: Long, password: String) {
        credentials.save(accountId, password)
    }

    suspend fun removeAccount(accountId: Long) {
        mutex.withLock {
            dao.deleteAccount(accountId)
            credentials.delete(accountId)
        }
        reminders.reschedule()
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
                mergeCollections(account.id, dav.discover(account.baseUrl).collections)
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
        val events = found.filter { it.supportsEvents }
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
}
