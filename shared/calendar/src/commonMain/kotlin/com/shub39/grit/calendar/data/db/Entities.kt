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

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A CalDAV account. The password is kept by the credential store, not in this table. One special
 * account with [local] set holds the calendars that exist only on this device; it never syncs.
 */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseUrl: String,
    val username: String,
    @ColumnInfo(defaultValue = "0") val local: Boolean = false,
    val principalUrl: String? = null,
    val homeSetUrl: String? = null,
    /** Epoch milliseconds of the last finished sync. */
    val lastSync: Long? = null,
    val lastError: String? = null,
)

/** A calendar collection of an account. */
@Entity(
    tableName = "collection",
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
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val url: String,
    val displayName: String? = null,
    /** ARGB color from the server, or null. */
    val color: Int? = null,
    val supportsEvents: Boolean = true,
    val supportsTasks: Boolean = false,
    /** The server allows writes (`DAV:write-content`). */
    val canWrite: Boolean = true,
    /** The collection was added by URL instead of found by discovery. */
    val manual: Boolean = false,
    val visible: Boolean = true,
    val syncEnabled: Boolean = true,
    val syncToken: String? = null,
    val ctag: String? = null,
    val lastSync: Long? = null,
    val lastError: String? = null,
)

/**
 * One event resource. [ics] is the full iCalendar text. The other columns are copies of its fields,
 * for lists that must not load the full text.
 */
@Entity(
    tableName = "event",
    foreignKeys =
        [
            ForeignKey(
                entity = CollectionEntity::class,
                parentColumns = ["id"],
                childColumns = ["collectionId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["collectionId", "href"], unique = true),
            Index(value = ["collectionId", "dirty"]),
        ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    /** Absolute URL of the resource on the server. */
    val href: String,
    val uid: String,
    val etag: String? = null,
    val ics: String,
    /** Local changes are waiting for upload. */
    val dirty: Boolean = false,
    /** Deleted locally, waiting for the server delete. */
    val deleted: Boolean = false,
    val version: Long = 0,
    val existsOnServer: Boolean = true,
    val summary: String? = null,
    val location: String? = null,
    val description: String? = null,
    val recurring: Boolean = false,
    val syncError: String? = null,
)

/** One occurrence of an event, expanded for a time window. */
@Entity(
    tableName = "instance",
    foreignKeys =
        [
            ForeignKey(
                entity = EventEntity::class,
                parentColumns = ["id"],
                childColumns = ["eventId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["eventId"]), Index(value = ["begin"]), Index(value = ["end"])],
)
data class InstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val collectionId: Long,
    /** Epoch milliseconds. All-day instances start at midnight UTC of their date. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    /** Original start in the series, or null for a single event. */
    val originalStart: Long? = null,
    val isOverride: Boolean = false,
    val title: String? = null,
    val location: String? = null,
)

/** When a reminder for an instance fires. */
@Entity(
    tableName = "alarm",
    foreignKeys =
        [
            ForeignKey(
                entity = EventEntity::class,
                parentColumns = ["id"],
                childColumns = ["eventId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["eventId"]), Index(value = ["triggerAt"])],
)
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    /** Epoch milliseconds. */
    val triggerAt: Long,
    val instanceBegin: Long,
    val instanceAllDay: Boolean,
    val title: String? = null,
    val location: String? = null,
)

/** The losing side of a sync conflict, kept for recovery. */
@Entity(tableName = "sync_conflict")
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val href: String,
    val localIcs: String,
    val remoteIcs: String?,
    val createdAt: Long,
)

/** Small key-value store for the expansion window and the alarm watermark. */
@Entity(tableName = "kv") data class KeyValueEntity(@PrimaryKey val key: String, val value: Long)
