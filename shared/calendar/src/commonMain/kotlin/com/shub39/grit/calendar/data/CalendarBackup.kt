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

import com.shub39.grit.caldav.RemoteItem
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.core.settings.backup.BackupContributor
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.core.annotation.Single

/** The calendar part of the Grit backup file. */
@Serializable
data class CalendarBackupSchema(
    val version: Int = CalendarBackup.VERSION,
    val categories: List<CategoryBackup> = emptyList(),
    val localCalendars: List<LocalCalendarBackup> = emptyList(),
    val accounts: List<AccountBackup> = emptyList(),
)

@Serializable data class CategoryBackup(val name: String, val color: String)

/** A calendar on this device, with the full iCalendar text of every event. */
@Serializable
data class LocalCalendarBackup(
    val url: String,
    val name: String? = null,
    val color: Int? = null,
    val visible: Boolean = true,
    val events: List<EventBackup> = emptyList(),
)

@Serializable data class EventBackup(val href: String, val ics: String)

/** A CalDAV account without its password. The events download again at the next sync. */
@Serializable
data class AccountBackup(
    val baseUrl: String,
    val username: String,
    val calendars: List<RemoteCalendarBackup> = emptyList(),
)

@Serializable
data class RemoteCalendarBackup(
    val url: String,
    val name: String? = null,
    val color: Int? = null,
    /** Added by URL: discovery does not find it again. */
    val manual: Boolean = false,
    val visible: Boolean = true,
    val syncEnabled: Boolean = true,
)

/**
 * Puts the calendars into the Grit backup file: categories, the calendars on this device with their
 * events, and the CalDAV account with its calendar settings. The password stays out of the file, so
 * a restored account asks for it again. Changes that are not uploaded yet are not in the file: sync
 * before a backup.
 */
@Single(binds = [BackupContributor::class])
class CalendarBackup(
    db: CalendarDatabase,
    private val store: CalendarStore,
    private val reminders: ReminderScheduler,
) : BackupContributor {
    private val dao = db.calendarDao()
    private val json = Json { ignoreUnknownKeys = true }

    override val key: String = "calendar"

    override suspend fun export(): JsonElement {
        val accounts = dao.accounts()
        val local = accounts.firstOrNull { it.local }
        val localCalendars =
            if (local == null) emptyList()
            else
                dao.collections(local.id).map { calendar ->
                    LocalCalendarBackup(
                        url = calendar.url,
                        name = calendar.displayName,
                        color = calendar.color,
                        visible = calendar.visible,
                        events = dao.eventsOf(calendar.id).map { EventBackup(it.href, it.ics) },
                    )
                }
        val remoteAccounts =
            accounts
                .filter { !it.local }
                .map { account ->
                    AccountBackup(
                        baseUrl = account.baseUrl,
                        username = account.username,
                        calendars =
                            dao.collections(account.id).map {
                                RemoteCalendarBackup(
                                    url = it.url,
                                    name = it.displayName,
                                    color = it.color,
                                    manual = it.manual,
                                    visible = it.visible,
                                    syncEnabled = it.syncEnabled,
                                )
                            },
                    )
                }
        val backup =
            CalendarBackupSchema(
                categories = dao.categoriesFlow().first().map { CategoryBackup(it.name, it.color) },
                localCalendars = localCalendars,
                accounts = remoteAccounts,
            )
        return json.encodeToJsonElement(backup)
    }

    override suspend fun restore(data: JsonElement) {
        val backup = json.decodeFromJsonElement<CalendarBackupSchema>(data)
        if (backup.version > VERSION) {
            throw SerializationException("Calendar backup version ${backup.version} is too new")
        }
        for (category in backup.categories) {
            dao.upsertCategory(CategoryEntity(category.name, category.color))
        }
        restoreLocalCalendars(backup.localCalendars)
        restoreAccount(backup.accounts.firstOrNull())
        reminders.reschedule()
        store.requestSync()
    }

    /** Calendars with the same URL merge: their events are added or replaced, never removed. */
    private suspend fun restoreLocalCalendars(calendars: List<LocalCalendarBackup>) {
        if (calendars.isEmpty()) return
        val account =
            dao.localAccount()?.id
                ?: dao.upsertAccount(AccountEntity(baseUrl = "", username = "", local = true))
        val existing = dao.collections(account).associateBy { it.url }
        for (calendar in calendars) {
            val id =
                existing[calendar.url]?.id
                    ?: dao.insertCollection(
                        CollectionEntity(
                            accountId = account,
                            url = calendar.url,
                            displayName = calendar.name,
                            color = calendar.color,
                            canWrite = true,
                            visible = calendar.visible,
                            syncEnabled = false,
                        )
                    )
            store.applyRemote(id, calendar.events.map { RemoteItem(it.href, null, it.ics) })
        }
    }

    /**
     * Grit shows one account. Without one, the backup account comes back without its password, and
     * the account screen asks for it. If the same account exists, the calendar switches and the
     * calendars added by URL come back. Another account stays as it is.
     */
    private suspend fun restoreAccount(backup: AccountBackup?) {
        if (backup == null) return
        val current = dao.accounts().filter { !it.local }
        val account =
            if (current.isEmpty()) {
                dao.upsertAccount(
                    AccountEntity(baseUrl = backup.baseUrl, username = backup.username)
                )
            } else {
                current
                    .firstOrNull { it.baseUrl == backup.baseUrl && it.username == backup.username }
                    ?.id ?: return
            }
        val existing = dao.collections(account).associateBy { it.url }
        for (calendar in backup.calendars) {
            val known = existing[calendar.url]
            if (known != null) {
                dao.updateCollection(
                    known.copy(visible = calendar.visible, syncEnabled = calendar.syncEnabled)
                )
            } else {
                dao.insertCollection(
                    CollectionEntity(
                        accountId = account,
                        url = calendar.url,
                        displayName = calendar.name,
                        color = calendar.color,
                        // The next sync reads the real access rights
                        canWrite = false,
                        manual = calendar.manual,
                        visible = calendar.visible,
                        syncEnabled = calendar.syncEnabled,
                    )
                )
            }
        }
    }

    companion object {
        const val VERSION = 1
    }
}
