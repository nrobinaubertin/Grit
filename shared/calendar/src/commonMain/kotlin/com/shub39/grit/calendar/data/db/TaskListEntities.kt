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

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A task list on the CalDAV server: a collection that holds to-dos (VTODO). It syncs with the Grit
 * task category [categoryId], or it does not sync if [categoryId] is null.
 */
@Entity(
    tableName = "task_list",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["accountId", "url"], unique = true)],
)
data class TaskListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val url: String,
    val displayName: String? = null,
    /** The server allows writes (`DAV:write-content`). */
    val canWrite: Boolean = true,
    /** The Grit task category that syncs with this list, or null. */
    val categoryId: Long? = null,
    /**
     * The first sync of the link is done: Grit tasks and server to-dos with the same title are
     * paired. Until then, nothing uploads.
     */
    val paired: Boolean = false,
    val syncToken: String? = null,
    val lastSync: Long? = null,
    val lastError: String? = null,
)

/**
 * One to-do of a synced task list, and the Grit task or subtask that shows it. [snapshot] holds the
 * Grit fields as Grit last saw them, so that a change made anywhere in Grit (task list, widget,
 * notification) is found by comparison.
 */
@Entity(
    tableName = "task_item",
    foreignKeys =
        [
            ForeignKey(
                entity = TaskListEntity::class,
                parentColumns = ["id"],
                childColumns = ["listId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["listId", "href"], unique = true),
            Index(value = ["taskId"]),
            Index(value = ["subTaskId"]),
        ],
)
data class TaskItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    /** Absolute URL of the resource on the server. */
    val href: String,
    val uid: String,
    val etag: String? = null,
    /**
     * Last known iCalendar text: from the server, or the last upload. Empty before the first
     * upload.
     */
    val ics: String = "",
    /** The Grit task, or null. At most one of [taskId] and [subTaskId] is set. */
    val taskId: Long? = null,
    val subTaskId: Long? = null,
    val snapshot: String = "",
    /** Local changes are waiting for upload. */
    val dirty: Boolean = false,
    /** Deleted in Grit, waiting for the server delete. */
    val deleted: Boolean = false,
    val version: Long = 0,
    val existsOnServer: Boolean = false,
    /** Epoch milliseconds of the last change in Grit. It decides conflicts. */
    val modifiedAt: Long = 0,
    /** The server version changed: the Grit task must take it. */
    val remoteChanged: Boolean = false,
)
