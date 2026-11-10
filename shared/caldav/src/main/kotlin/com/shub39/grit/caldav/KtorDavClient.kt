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

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.ForbiddenException
import at.bitfire.dav4jvm.ktor.exception.GoneException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.GetCTag
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.SyncToken
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.withCharset
import java.io.Closeable
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.flow.toList

/** [DavClient] on top of dav4jvm and Ktor (OkHttp engine). Close it when done. */
class KtorDavClient(private val client: HttpClient) : DavClient, Closeable {

    constructor(
        baseUrl: String,
        credentials: Credentials,
    ) : this(createHttpClient(Url(normalizeUrl(baseUrl)).host, credentials))

    override suspend fun discover(baseUrl: String): Discovery = translate {
        val start = Url(normalizeUrl(baseUrl))
        var principal: String? = null
        var home: String? = null
        for (candidate in listOf(start, Url(resolve(start.toString(), "/.well-known/caldav")))) {
            val self =
                try {
                    propfindSelf(
                        candidate,
                        WebDAV.ResourceType,
                        WebDAV.CurrentUserPrincipal,
                        CalDAV.CalendarHomeSet,
                    )
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (_: HttpException) {
                    null
                } ?: continue

            // The user entered the URL of a calendar
            if (self.isCalendar()) {
                return@translate Discovery(
                    null,
                    null,
                    listOfNotNull(collection(candidate.toString())),
                )
            }

            val base = self.href.toString()
            home =
                self[CalendarHomeSet::class.java]?.hrefs?.firstOrNull()?.let { resolve(base, it) }
            principal = self[CurrentUserPrincipal::class.java]?.href?.let { resolve(base, it) }
            if (home == null && principal != null) {
                val principalResponse = propfindSelf(Url(principal), CalDAV.CalendarHomeSet)
                home =
                    principalResponse?.get(CalendarHomeSet::class.java)?.hrefs?.firstOrNull()?.let {
                        resolve(principal, it)
                    }
            }
            if (home != null) break
        }
        val homeUrl = home ?: throw IOException("No CalDAV calendars found at $baseUrl")
        val collections =
            DavResource(client, Url(homeUrl))
                .propfind(1, *COLLECTION_PROPERTIES)
                .responses()
                .toList()
                .filter { it.isSuccess() && it.isCalendar() }
                .map { it.toCollectionInfo() }
                .sortedBy { it.displayName ?: it.url }
        Discovery(principal, homeUrl, collections)
    }

    override suspend fun collection(url: String): CollectionInfo? = translate {
        propfindSelf(Url(normalizeUrl(url)), *COLLECTION_PROPERTIES)
            ?.takeIf { it.isCalendar() }
            ?.toCollectionInfo()
    }

    override suspend fun syncCollection(url: String, token: String?): SyncDelta = translate {
        val items =
            try {
                DavCollection(client, Url(url))
                    .reportChanges(token, false, null, WebDAV.GetETag)
                    .toList()
            } catch (e: HttpException) {
                // RFC 6578: an unknown or expired token gives 403 (or 409) valid-sync-token
                val code = e.statusCode
                if (token != null && (code == 403 || code == 409))
                    throw InvalidSyncTokenException(e)
                throw e
            }
        val changed = mutableListOf<RemoteItem>()
        val removed = mutableListOf<String>()
        var truncated = false
        var newToken: String? = null
        for (item in items) {
            when (item) {
                is MultiStatusItem.ExtraProperty ->
                    (item.property as? SyncToken)?.token?.let { newToken = it }
                is MultiStatusItem.Response -> {
                    val response = item.response
                    val href = response.href.toString()
                    if (sameUrl(href, url)) {
                        if (response.status == HttpStatusCode.InsufficientStorage) truncated = true
                        continue
                    }
                    when {
                        response.status == HttpStatusCode.NotFound -> removed += href
                        response.isSuccess() ->
                            changed += RemoteItem(href, response[GetETag::class.java]?.rawETag)
                    }
                }
                else -> Unit
            }
        }
        SyncDelta(changed, removed, newToken, truncated)
    }

    override suspend fun listEtags(url: String, type: ComponentType): List<RemoteItem> = translate {
        DavCalendar(client, Url(url))
            .calendarQuery(type.name, null, null, setOf(WebDAV.GetETag), Headers.Empty)
            .responses()
            .toList()
            .filter { it.isSuccess() && !sameUrl(it.href.toString(), url) }
            .map { RemoteItem(it.href.toString(), it[GetETag::class.java]?.rawETag) }
    }

    override suspend fun multiget(url: String, hrefs: List<String>): List<RemoteItem> = translate {
        if (hrefs.isEmpty()) return@translate emptyList()
        DavCalendar(client, Url(url))
            .multiget(hrefs.map { Url(it) })
            .responses()
            .toList()
            .filter { it.isSuccess() }
            .mapNotNull { response ->
                val ics = response[CalendarData::class.java]?.iCalendar ?: return@mapNotNull null
                RemoteItem(response.href.toString(), response[GetETag::class.java]?.rawETag, ics)
            }
    }

    override suspend fun put(href: String, ics: String, condition: WriteCondition): WriteResult =
        write {
            val headers = condition.headers()
            val body = TextContent(ics, ContentType("text", "calendar").withCharset(Charsets.UTF_8))
            DavResource(client, Url(href)).put(body, headers) { response ->
                WriteResult.Ok(response.headers[HttpHeaders.ETag])
            }
        }

    override suspend fun delete(href: String, condition: WriteCondition): WriteResult = write {
        DavResource(client, Url(href)).delete(condition.headers()) { WriteResult.Ok(null) }
    }

    override suspend fun createCalendar(
        url: String,
        displayName: String,
        color: Int?,
    ): WriteResult = write {
        val colorProperty =
            color?.let { "<I:calendar-color>#%06X</I:calendar-color>".format(it and 0xFFFFFF) }
        val body =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<C:mkcalendar xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" " +
                "xmlns:I=\"http://apple.com/ns/ical/\"><D:set><D:prop>" +
                "<D:displayname>${escapeXml(displayName)}</D:displayname>" +
                colorProperty.orEmpty() +
                "<C:supported-calendar-component-set><C:comp name=\"VEVENT\"/>" +
                "</C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>"
        DavResource(client, Url(url)).mkCol(body, "MKCALENDAR") { WriteResult.Ok(null) }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    override fun close() = client.close()

    private suspend fun propfindSelf(url: Url, vararg properties: Property.Name): Response? =
        DavResource(client, url).propfind(0, *properties).responses().toList().firstOrNull {
            it.isSuccess()
        }

    private fun Response.isCalendar(): Boolean =
        this[ResourceType::class.java]?.types?.contains(CalDAV.Calendar) == true

    private fun Response.toCollectionInfo(): CollectionInfo {
        val components = this[SupportedCalendarComponentSet::class.java]
        return CollectionInfo(
            url = href.toString(),
            displayName = this[DisplayName::class.java]?.displayName?.takeIf { it.isNotBlank() },
            color = this[CalendarColor::class.java]?.color,
            // A missing component set means that the collection accepts every component type
            supportsEvents = components?.supportsEvents ?: true,
            supportsTasks = components?.supportsTasks ?: true,
            canWrite = this[CurrentUserPrivilegeSet::class.java]?.mayWriteContent ?: true,
            ctag = this[GetCTag::class.java]?.cTag,
            syncToken = this[SyncToken::class.java]?.token,
        )
    }

    /** Maps HTTP errors of a write to [WriteResult]. A 401 still throws. */
    private suspend fun write(block: suspend () -> WriteResult): WriteResult =
        try {
            block()
        } catch (_: PreconditionFailedException) {
            WriteResult.Precondition
        } catch (_: NotFoundException) {
            WriteResult.NotFound
        } catch (_: GoneException) {
            WriteResult.NotFound
        } catch (_: ForbiddenException) {
            WriteResult.Forbidden
        } catch (e: UnauthorizedException) {
            throw AuthenticationException(e.message ?: "Unauthorized")
        } catch (e: HttpException) {
            WriteResult.Failed(e.statusCode, e.message)
        }

    /** Turns a 401 into [AuthenticationException]. */
    private suspend fun <T> translate(block: suspend () -> T): T =
        try {
            block()
        } catch (e: UnauthorizedException) {
            throw AuthenticationException(e.message ?: "Unauthorized")
        }

    companion object {
        private val COLLECTION_PROPERTIES =
            arrayOf(
                WebDAV.ResourceType,
                WebDAV.DisplayName,
                CalDAV.CalendarColor,
                CalDAV.SupportedCalendarComponentSet,
                WebDAV.CurrentUserPrivilegeSet,
                CalDAV.GetCTag,
                WebDAV.SyncToken,
            )

        /**
         * Creates the HTTP client. Credentials are sent with the first request (no extra 401 round
         * trip), but only to [host], so that a redirect to another host does not leak them.
         */
        fun createHttpClient(host: String, credentials: Credentials): HttpClient =
            HttpClient(OkHttp) {
                // dav4jvm follows redirects itself
                followRedirects = false
                expectSuccess = false
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials(credentials.username, credentials.password)
                        }
                        sendWithoutRequest { it.url.host == host }
                    }
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 60_000
                    requestTimeoutMillis = 120_000
                }
                install(UserAgent) { agent = "Grit-Calendar" }
            }

        /** Adds `https://` if the user typed only a host name. */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return if ("://" in trimmed) trimmed else "https://$trimmed"
        }

        internal fun resolve(base: String, href: String): String =
            URI(base).resolve(href).toString()

        private fun sameUrl(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

        private fun WriteCondition.headers(): Headers =
            when (this) {
                WriteCondition.MustNotExist -> headersOf(HttpHeaders.IfNoneMatch, "*")
                WriteCondition.None -> Headers.Empty
                // If-Match only works with strong ETags. A weak ETag cannot protect a write.
                is WriteCondition.IfMatch ->
                    if (etag.startsWith("W/")) Headers.Empty
                    else
                        headersOf(
                            HttpHeaders.IfMatch,
                            if (etag.startsWith("\"")) etag else "\"$etag\"",
                        )
            }
    }
}
