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
package com.shub39.grit.calendar.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** An href and its ETag. */
data class HrefEtag(val href: String, val etag: String?)

/** How many events of a category a calendar holds. */
data class CategoryUse(val category: String, val calendar: String?, val events: Int)

/** An instance with the display data of its calendar. */
data class InstanceRow(
    val id: Long,
    val eventId: Long,
    val collectionId: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val originalStart: Long?,
    val title: String?,
    val location: String?,
    val category: String?,
    /** CSS color name of the category. */
    val categoryColor: String?,
    val calendarName: String?,
    val canWrite: Boolean,
    val recurring: Boolean,
)

@Dao
interface CalendarDao {
    // --- accounts ---

    @Query("SELECT * FROM account ORDER BY id") fun accountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY id") suspend fun accounts(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id") suspend fun account(id: Long): AccountEntity?

    @Query("SELECT * FROM account WHERE local = 1 LIMIT 1")
    suspend fun localAccount(): AccountEntity?

    /** True if the calendar belongs to the local account (it never syncs). */
    @Query("SELECT a.local FROM collection c JOIN account a ON a.id = c.accountId WHERE c.id = :id")
    suspend fun isLocalCollection(id: Long): Boolean?

    @Upsert suspend fun upsertAccount(account: AccountEntity): Long

    @Query("DELETE FROM account WHERE id = :id") suspend fun deleteAccount(id: Long)

    @Query("UPDATE account SET lastSync = :at, lastError = :error WHERE id = :id")
    suspend fun setAccountSyncResult(id: Long, at: Long, error: String?)

    // --- collections ---

    @Query("SELECT * FROM collection ORDER BY displayName COLLATE NOCASE, url")
    fun collectionsFlow(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection WHERE accountId = :accountId")
    suspend fun collections(accountId: Long): List<CollectionEntity>

    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun collection(id: Long): CollectionEntity?

    @Insert suspend fun insertCollection(collection: CollectionEntity): Long

    @Update suspend fun updateCollection(collection: CollectionEntity)

    @Query("DELETE FROM collection WHERE id = :id") suspend fun deleteCollection(id: Long)

    @Query("UPDATE collection SET syncToken = :token, ctag = :ctag WHERE id = :id")
    suspend fun setSyncState(id: Long, token: String?, ctag: String?)

    @Query("UPDATE collection SET canWrite = :canWrite WHERE id = :id")
    suspend fun setCanWrite(id: Long, canWrite: Boolean)

    @Query("UPDATE collection SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("UPDATE collection SET syncEnabled = :enabled WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE collection SET lastSync = :at, lastError = :error WHERE id = :id")
    suspend fun setCollectionSyncResult(id: Long, at: Long, error: String?)

    // --- events ---

    @Query("SELECT * FROM event WHERE id = :id") suspend fun event(id: Long): EventEntity?

    @Query("SELECT * FROM event WHERE collectionId = :collectionId AND href = :href")
    suspend fun eventByHref(collectionId: Long, href: String): EventEntity?

    @Query("SELECT * FROM event WHERE collectionId = :collectionId AND uid = :uid LIMIT 1")
    suspend fun eventByUid(collectionId: Long, uid: String): EventEntity?

    @Query("SELECT * FROM event WHERE collectionId = :collectionId AND deleted = 0 ORDER BY id")
    suspend fun eventsOf(collectionId: Long): List<EventEntity>

    @Query("SELECT * FROM event WHERE collectionId = :collectionId AND dirty = 1")
    suspend fun pendingEvents(collectionId: Long): List<EventEntity>

    @Query("SELECT COUNT(*) FROM event WHERE dirty = 1") fun pendingCountFlow(): Flow<Int>

    @Query("SELECT href, etag FROM event WHERE collectionId = :collectionId AND existsOnServer = 1")
    suspend fun knownEtags(collectionId: Long): List<HrefEtag>

    @Query("SELECT id FROM event WHERE deleted = 0") suspend fun eventIds(): List<Long>

    @Query("SELECT id FROM event WHERE deleted = 0 AND recurring = 1")
    suspend fun recurringEventIds(): List<Long>

    @Insert suspend fun insertEvent(event: EventEntity): Long

    @Update suspend fun updateEvent(event: EventEntity)

    @Query("DELETE FROM event WHERE id = :id") suspend fun deleteEvent(id: Long)

    @Query(
        "SELECT id FROM event WHERE collectionId = :collectionId AND href IN (:hrefs) AND dirty = 0"
    )
    suspend fun cleanEventIds(collectionId: Long, hrefs: List<String>): List<Long>

    /** Returns 1 if the row still had [version], so that its pending flag was cleared. */
    @Query(
        "UPDATE event SET dirty = 0, etag = :etag, existsOnServer = 1, syncError = NULL " +
            "WHERE id = :id AND version = :version"
    )
    suspend fun markUploaded(id: Long, version: Long, etag: String?): Int

    @Query("UPDATE event SET etag = :etag, existsOnServer = 1 WHERE id = :id")
    suspend fun setUploadedEtag(id: Long, etag: String?)

    // --- instances and alarms ---

    @Query("DELETE FROM instance WHERE eventId = :eventId")
    suspend fun deleteInstances(eventId: Long)

    @Insert suspend fun insertInstances(instances: List<InstanceEntity>)

    @Query(
        """
        SELECT i.id, i.eventId, i.collectionId, i.begin, i.`end`, i.allDay, i.originalStart,
            i.title, i.location, e.category, cat.color AS categoryColor,
            c.displayName AS calendarName, c.canWrite, e.recurring
        FROM instance i
        JOIN collection c ON c.id = i.collectionId
        JOIN event e ON e.id = i.eventId
        LEFT JOIN category cat ON cat.name = e.category
        WHERE c.visible = 1 AND e.deleted = 0 AND i.begin < :to
            AND (i.`end` > :from OR (i.begin = i.`end` AND i.begin >= :from))
        ORDER BY i.begin, i.allDay DESC, i.`end`
        """
    )
    fun instancesFlow(from: Long, to: Long): Flow<List<InstanceRow>>

    @Query("SELECT * FROM instance WHERE id = :id") suspend fun instance(id: Long): InstanceEntity?

    @Query("DELETE FROM alarm WHERE eventId = :eventId") suspend fun deleteAlarms(eventId: Long)

    @Insert suspend fun insertAlarms(alarms: List<AlarmEntity>)

    @Query(
        """
        SELECT a.* FROM alarm a
        JOIN event e ON e.id = a.eventId
        JOIN collection c ON c.id = e.collectionId
        WHERE c.visible = 1 AND e.deleted = 0 AND a.triggerAt > :after
        ORDER BY a.triggerAt LIMIT 1
        """
    )
    suspend fun nextAlarm(after: Long): AlarmEntity?

    @Query(
        """
        SELECT a.* FROM alarm a
        JOIN event e ON e.id = a.eventId
        JOIN collection c ON c.id = e.collectionId
        WHERE c.visible = 1 AND e.deleted = 0 AND a.triggerAt > :after AND a.triggerAt <= :until
        ORDER BY a.triggerAt
        """
    )
    suspend fun alarmsBetween(after: Long, until: Long): List<AlarmEntity>

    // --- categories ---

    @Query("SELECT * FROM category ORDER BY name COLLATE NOCASE")
    fun categoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE name = :name")
    suspend fun category(name: String): CategoryEntity?

    @Upsert suspend fun upsertCategory(category: CategoryEntity)

    @Query("DELETE FROM category WHERE name = :name") suspend fun deleteCategory(name: String)

    /** Events of a category in calendars that the user may change. */
    @Query(
        """
        SELECT e.* FROM event e JOIN collection c ON c.id = e.collectionId
        WHERE e.category = :name AND e.deleted = 0 AND c.canWrite = 1
        """
    )
    suspend fun writableEventsWithCategory(name: String): List<EventEntity>

    @Query("SELECT * FROM event WHERE category IS NULL AND deleted = 0")
    suspend fun eventsWithoutCategory(): List<EventEntity>

    /** The calendars that hold events of each category. */
    @Query(
        """
        SELECT e.category AS category, c.displayName AS calendar, COUNT(*) AS events
        FROM event e JOIN collection c ON c.id = e.collectionId
        WHERE e.category IS NOT NULL AND e.deleted = 0
        GROUP BY e.category, c.id
        ORDER BY c.displayName COLLATE NOCASE
        """
    )
    fun categoryUsesFlow(): Flow<List<CategoryUse>>

    @Query("SELECT COUNT(*) FROM event WHERE category = :name AND deleted = 0")
    suspend fun countEventsWithCategory(name: String): Int

    // --- conflicts and settings ---

    @Insert suspend fun insertConflict(conflict: ConflictEntity)

    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun getValue(key: String): Long?

    @Upsert suspend fun putValue(entry: KeyValueEntity)

    @Query("SELECT `key` FROM kv WHERE `key` LIKE :prefix || '%'")
    suspend fun keysWithPrefix(prefix: String): List<String>

    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteValue(key: String)

    @Query("SELECT `key` FROM kv WHERE `key` LIKE :prefix || '%' AND value = :value")
    suspend fun keysWithValue(prefix: String, value: Long): List<String>

    /** Settings whose key starts with [prefix]. */
    @Query("SELECT * FROM kv WHERE `key` LIKE :prefix || '%'")
    fun valuesFlow(prefix: String): Flow<List<KeyValueEntity>>
}
