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
package com.shub39.grit.caldav

/** The kind of iCalendar component a collection holds. */
enum class ComponentType {
    VEVENT,
    VTODO,
}

/** Login for a CalDAV server. */
data class Credentials(val username: String, val password: String)

/** A calendar collection on the server, as found by discovery. */
data class CollectionInfo(
    val url: String,
    val displayName: String?,
    /** Color as ARGB, or null if the server has none. */
    val color: Int?,
    val supportsEvents: Boolean,
    val supportsTasks: Boolean,
    /** True if the user may create, change and delete items (`DAV:write-content`). */
    val canWrite: Boolean,
    val ctag: String?,
    val syncToken: String?,
)

/** What discovery found for an account. */
data class Discovery(
    val principalUrl: String?,
    val homeSetUrl: String?,
    val collections: List<CollectionInfo>,
)

/** One item on the server. [href] is an absolute URL, [etag] is the raw (quoted) ETag. */
data class RemoteItem(val href: String, val etag: String?, val ics: String? = null)

/** Changes in a collection since a sync token. */
data class SyncDelta(
    val changed: List<RemoteItem>,
    val removed: List<String>,
    val token: String?,
    /** True if the server returned only part of the changes (call again with [token]). */
    val truncated: Boolean,
)

/** Condition for a PUT or DELETE. */
sealed interface WriteCondition {
    /** Write only if the item does not exist yet (`If-None-Match: *`). */
    data object MustNotExist : WriteCondition

    /** Write only if the item still has this ETag (`If-Match`). */
    data class IfMatch(val etag: String) : WriteCondition

    /** Write unconditionally. Used only when the ETag is unknown. */
    data object None : WriteCondition
}

/** Result of a PUT or DELETE. */
sealed interface WriteResult {
    /** Success. [etag] is the new ETag, if the server sent one. */
    data class Ok(val etag: String?) : WriteResult

    /** The item changed on the server since we read it (HTTP 412). */
    data object Precondition : WriteResult

    /** The item does not exist on the server (HTTP 404 or 410). */
    data object NotFound : WriteResult

    /** The user may not write here (HTTP 403). */
    data object Forbidden : WriteResult

    data class Failed(val code: Int?, val message: String?) : WriteResult
}

/** The server rejected the login (HTTP 401). */
class AuthenticationException(message: String) : Exception(message)

/** The server no longer accepts the stored sync token. A full comparison is needed. */
class InvalidSyncTokenException(cause: Throwable?) : Exception("Invalid sync token", cause)

/** Access to a CalDAV server. All URLs are absolute. */
interface DavClient {
    /** Finds the user's calendars from a server URL, a principal URL or a home URL. */
    suspend fun discover(baseUrl: String): Discovery

    /** Reads one collection, for example a calendar that a user added by URL. */
    suspend fun collection(url: String): CollectionInfo?

    /** Changes since [token]. A null token lists everything. */
    suspend fun syncCollection(url: String, token: String?): SyncDelta

    /** All items of [type] in the collection, with their ETags but without data. */
    suspend fun listEtags(url: String, type: ComponentType): List<RemoteItem>

    /** Downloads the data of the given items. */
    suspend fun multiget(url: String, hrefs: List<String>): List<RemoteItem>

    suspend fun put(href: String, ics: String, condition: WriteCondition): WriteResult

    suspend fun delete(href: String, condition: WriteCondition): WriteResult

    /**
     * Creates a calendar for events (MKCALENDAR). [url] ends with a slash and does not exist yet.
     * [color] is ARGB, or null for the server's default.
     */
    suspend fun createCalendar(url: String, displayName: String, color: Int?): WriteResult
}
