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
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskListDao {
    // --- lists ---

    @Query("SELECT * FROM task_list ORDER BY displayName COLLATE NOCASE, id")
    fun listsFlow(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_list WHERE accountId = :accountId ORDER BY id")
    suspend fun lists(accountId: Long): List<TaskListEntity>

    @Query("SELECT * FROM task_list WHERE categoryId IS NOT NULL ORDER BY id")
    suspend fun linkedLists(): List<TaskListEntity>

    @Query("SELECT * FROM task_list WHERE id = :id") suspend fun list(id: Long): TaskListEntity?

    @Insert suspend fun insertList(list: TaskListEntity): Long

    @Update suspend fun updateList(list: TaskListEntity)

    @Query("DELETE FROM task_list WHERE id = :id") suspend fun deleteList(id: Long)

    @Query("UPDATE task_list SET syncToken = :token WHERE id = :id")
    suspend fun setSyncToken(id: Long, token: String?)

    @Query("UPDATE task_list SET paired = 1 WHERE id = :id") suspend fun setPaired(id: Long)

    @Query("UPDATE task_list SET canWrite = :canWrite WHERE id = :id")
    suspend fun setCanWrite(id: Long, canWrite: Boolean)

    @Query("UPDATE task_list SET lastSync = :time, lastError = :error WHERE id = :id")
    suspend fun setSyncResult(id: Long, time: Long, error: String?)

    // --- items ---

    @Query("SELECT * FROM task_item WHERE listId = :listId ORDER BY id")
    suspend fun items(listId: Long): List<TaskItemEntity>

    @Query("SELECT * FROM task_item WHERE id = :id") suspend fun item(id: Long): TaskItemEntity?

    @Query("SELECT * FROM task_item WHERE listId = :listId AND href = :href")
    suspend fun itemByHref(listId: Long, href: String): TaskItemEntity?

    @Query("SELECT href, etag FROM task_item WHERE listId = :listId AND existsOnServer = 1")
    suspend fun knownEtags(listId: Long): List<HrefEtag>

    @Query(
        "SELECT * FROM task_item WHERE listId = :listId AND (dirty = 1 OR deleted = 1) " +
            "AND remoteChanged = 0 ORDER BY id"
    )
    suspend fun pendingItems(listId: Long): List<TaskItemEntity>

    @Query("SELECT * FROM task_item WHERE listId = :listId AND remoteChanged = 1 ORDER BY id")
    suspend fun remoteChangedItems(listId: Long): List<TaskItemEntity>

    @Insert suspend fun insertItem(item: TaskItemEntity): Long

    @Update suspend fun updateItem(item: TaskItemEntity)

    @Query("DELETE FROM task_item WHERE id = :id") suspend fun deleteItem(id: Long)

    @Query("DELETE FROM task_item WHERE listId = :listId") suspend fun deleteItems(listId: Long)
}
