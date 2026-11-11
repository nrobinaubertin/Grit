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
package com.shub39.grit.caldav.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CollectionSyncerTest {
    private val server = FakeServer()
    private val local = FakeLocal()
    private val syncer = CollectionSyncer(server)
    private val a = "${COLLECTION}a.ics"
    private val b = "${COLLECTION}b.ics"

    @Test
    fun `first sync downloads everything`() = runTest {
        server.serverPut(a, ics("a", "A"))
        server.serverPut(b, ics("b", "B"))
        val report = syncer.sync(local)
        assertEquals(2, report.downloaded)
        assertEquals(setOf(a, b), local.rows.values.map { it.href }.toSet())
        assertEquals("t2", local.state.syncToken)
    }

    @Test
    fun `second sync only fetches changes`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        server.serverPut(b, ics("b", "B"))
        server.serverDelete(a)
        val report = syncer.sync(local)
        assertEquals(1, report.downloaded)
        assertEquals(1, report.removedLocally)
        assertEquals(listOf(b), local.rows.values.map { it.href })
        assertTrue("SYNC t1" in server.requests)
    }

    @Test
    fun `local creation is uploaded and not downloaded again`() = runTest {
        local.create("new", ics("new", "New"))
        val report = syncer.sync(local)
        assertEquals(1, report.uploaded)
        assertEquals(0, report.downloaded)
        val row = local.byHref("${COLLECTION}new.ics")!!
        assertFalse(row.dirty)
        assertEquals(server.items.getValue(row.href).first, row.etag)
        assertTrue(server.requests.any { it.endsWith("MustNotExist") })
    }

    @Test
    fun `local edit uses If-Match`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.edit(local.idOf(a), ics("a", "A2"))
        syncer.sync(local)
        assertEquals(ics("a", "A2"), server.items.getValue(a).second)
        assertTrue(server.requests.any { it.startsWith("PUT $a IfMatch") })
    }

    @Test
    fun `conflict - server wins when its change is newer`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.edit(local.idOf(a), ics("a", "local", "20260102T000000Z"))
        server.serverPut(a, ics("a", "server", "20260103T000000Z"))
        val report = syncer.sync(local)
        assertEquals(1, report.conflicts)
        assertEquals(ics("a", "server", "20260103T000000Z"), local.byHref(a)!!.ics)
        assertEquals(ics("a", "local", "20260102T000000Z"), local.conflicts.single().first)
    }

    @Test
    fun `conflict - local wins when its change is newer`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        server.serverPut(a, ics("a", "server", "20260102T000000Z"))
        local.edit(local.idOf(a), ics("a", "local", "20260103T000000Z"))
        syncer.sync(local)
        assertEquals(ics("a", "local", "20260103T000000Z"), server.items.getValue(a).second)
        assertFalse(local.byHref(a)!!.dirty)
    }

    @Test
    fun `local delete of an item changed on the server keeps the server version`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.delete(local.idOf(a))
        server.serverPut(a, ics("a", "changed"))
        syncer.sync(local)
        assertEquals(ics("a", "changed"), server.items.getValue(a).second)
        assertEquals(ics("a", "changed"), local.byHref(a)!!.ics)
    }

    @Test
    fun `local delete is propagated`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.delete(local.idOf(a))
        val report = syncer.sync(local)
        assertEquals(1, report.deletedRemotely)
        assertTrue(server.items.isEmpty())
        assertTrue(local.rows.isEmpty())
    }

    @Test
    fun `edit of an item deleted on the server recreates it`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.edit(local.idOf(a), ics("a", "kept"))
        server.serverDelete(a)
        syncer.sync(local)
        assertEquals(ics("a", "kept"), server.items.getValue(a).second)
        assertEquals(ics("a", "kept"), local.byHref(a)!!.ics)
    }

    @Test
    fun `invalid sync token falls back to a full comparison`() = runTest {
        server.serverPut(a, ics("a", "A"))
        server.serverPut(b, ics("b", "B"))
        syncer.sync(local)
        server.acceptTokens = false
        server.serverDelete(a)
        server.serverPut(b, ics("b", "B2"))
        val report = syncer.sync(local)
        assertEquals(1, report.removedLocally)
        assertEquals(1, report.downloaded)
        assertNull(local.byHref(a))
        assertEquals(ics("b", "B2"), local.byHref(b)!!.ics)
    }

    @Test
    fun `a sync without token removes what the server no longer lists`() = runTest {
        server.serverPut(a, ics("a", "A"))
        server.serverPut(b, ics("b", "B"))
        syncer.sync(local)
        // As after "Start syncing" reconnects a calendar: known items, but no token
        local.state = SyncState(null, null)
        server.serverDelete(a)
        val report = syncer.sync(local)
        assertEquals(1, report.removedLocally)
        assertNull(local.byHref(a))
        assertEquals(ics("b", "B"), local.byHref(b)!!.ics)
    }

    @Test
    fun `losing write access makes the collection read-only`() = runTest {
        server.serverPut(a, ics("a", "A"))
        syncer.sync(local)
        local.edit(local.idOf(a), ics("a", "mine"))
        server.canWrite = false
        val report = syncer.sync(local)
        assertTrue(report.becameReadOnly)
        assertTrue(local.readOnly)
        assertEquals(ics("a", "A"), local.byHref(a)!!.ics)
        assertFalse(local.byHref(a)!!.dirty)
    }

    @Test
    fun `an edit made during the upload stays pending`() = runTest {
        val id = local.create("new", ics("new", "first"))
        server.beforePut = { local.edit(id, ics("new", "second")) }
        syncer.sync(local)
        assertTrue(local.rows.getValue(id).dirty)
        server.beforePut = {}
        syncer.sync(local)
        assertEquals(ics("new", "second"), server.items.getValue("${COLLECTION}new.ics").second)
        assertFalse(local.rows.getValue(id).dirty)
    }
}
