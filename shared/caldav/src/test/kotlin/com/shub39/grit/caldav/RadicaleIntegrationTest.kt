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

import com.shub39.grit.caldav.sync.CollectionSyncer
import com.shub39.grit.caldav.sync.FakeLocal
import com.shub39.grit.caldav.sync.ics
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * Runs against the development server from `server/radicale/run-dev.sh`. Skipped unless
 * RADICALE_URL is set, for example `RADICALE_URL=http://127.0.0.1:5232`.
 */
class RadicaleIntegrationTest {
    private val baseUrl = System.getenv("RADICALE_URL").orEmpty().trimEnd('/')

    @BeforeEach
    fun requireServer() {
        assumeTrue(baseUrl.isNotEmpty(), "RADICALE_URL is not set")
    }

    private fun client(user: String) = KtorDavClient(baseUrl, Credentials(user, user))

    @Test
    fun `discovery finds own and shared calendars with their access rights`() =
        runBlocking<Unit> {
            client("bob").use { dav ->
                val discovery = dav.discover(baseUrl)
                val byName =
                    discovery.collections.associateBy {
                        it.url.trimEnd('/').substringAfterLast('/')
                    }
                assertEquals(setOf("work", "alice-personal", "family"), byName.keys)
                assertTrue(byName.getValue("work").canWrite)
                assertFalse(byName.getValue("alice-personal").canWrite)
                assertTrue(byName.getValue("family").canWrite)
                assertTrue(byName.getValue("family").supportsEvents)
                assertEquals("$baseUrl/bob/", discovery.homeSetUrl)
            }
        }

    @Test
    fun `wrong password is reported`() =
        runBlocking<Unit> {
            KtorDavClient(baseUrl, Credentials("bob", "wrong")).use { dav ->
                val error = runCatching { dav.discover(baseUrl) }.exceptionOrNull()
                assertIs<AuthenticationException>(error)
            }
        }

    @Test
    fun `write, change, read and delete an item`() =
        runBlocking<Unit> {
            client("bob").use { dav ->
                val collection = "$baseUrl/bob/work/"
                val start = dav.syncCollection(collection, null)
                val uid = UUID.randomUUID().toString()
                val href = "$collection$uid.ics"

                val created = dav.put(href, ics(uid, "Created"), WriteCondition.MustNotExist)
                assertIs<WriteResult.Ok>(created)
                assertEquals(
                    WriteResult.Precondition,
                    dav.put(href, ics(uid, "Again"), WriteCondition.MustNotExist),
                )

                val delta = dav.syncCollection(collection, start.token)
                assertTrue(delta.changed.any { it.href == href })

                val etag =
                    dav.listEtags(collection, ComponentType.VEVENT)
                        .single { it.href == href }
                        .etag!!
                assertEquals(
                    WriteResult.Precondition,
                    dav.put(href, ics(uid, "x"), WriteCondition.IfMatch("\"stale\"")),
                )
                assertIs<WriteResult.Ok>(
                    dav.put(href, ics(uid, "Changed"), WriteCondition.IfMatch(etag))
                )
                val fetched = dav.multiget(collection, listOf(href)).single()
                assertTrue("SUMMARY:Changed" in fetched.ics!!)

                assertIs<WriteResult.Ok>(dav.delete(href, WriteCondition.IfMatch(fetched.etag!!)))
                val after = dav.syncCollection(collection, delta.token)
                assertTrue(href in after.removed)
            }
        }

    @Test
    fun `read-only share refuses writes`() =
        runBlocking<Unit> {
            client("bob").use { dav ->
                val uid = UUID.randomUUID().toString()
                val result =
                    dav.put(
                        "$baseUrl/bob/alice-personal/$uid.ics",
                        ics(uid, "Nope"),
                        WriteCondition.MustNotExist,
                    )
                assertEquals(WriteResult.Forbidden, result)
            }
        }

    @Test
    fun `two users converge on a shared calendar`() =
        runBlocking<Unit> {
            val aliceDav = client("alice")
            val bobDav = client("bob")
            val aliceLocal = FakeLocal(url = "$baseUrl/alice/family/")
            val bobLocal = FakeLocal(url = "$baseUrl/bob/family/")
            val aliceSync = CollectionSyncer(aliceDav)
            val bobSync = CollectionSyncer(bobDav)
            try {
                aliceSync.sync(aliceLocal)
                bobSync.sync(bobLocal)
                val uid = UUID.randomUUID().toString()
                aliceLocal.create(uid, ics(uid, "Dinner at grandma's"))
                aliceSync.sync(aliceLocal)
                bobSync.sync(bobLocal)
                val bobRow = bobLocal.rows.entries.single { uid in it.value.href }
                assertTrue("Dinner at grandma's" in bobRow.value.ics)

                bobLocal.edit(bobRow.key, ics(uid, "Dinner moved", "20991231T000000Z"))
                bobSync.sync(bobLocal)
                aliceSync.sync(aliceLocal)
                assertTrue(
                    aliceLocal.rows.values.single { uid in it.href }.ics.contains("Dinner moved")
                )

                aliceLocal.delete(aliceLocal.rows.entries.single { uid in it.value.href }.key)
                aliceSync.sync(aliceLocal)
                bobSync.sync(bobLocal)
                assertTrue(bobLocal.rows.values.none { uid in it.href })
            } finally {
                aliceDav.close()
                bobDav.close()
            }
        }
}
