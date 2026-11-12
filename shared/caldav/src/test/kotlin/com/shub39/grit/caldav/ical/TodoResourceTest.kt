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
package com.shub39.grit.caldav.ical

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime

class TodoResourceTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-12T10:00:00Z")

    private fun ics(body: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VTODO\r\n" +
            body.trimIndent().replace("\n", "\r\n") +
            "\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    private fun info(text: String) = TodoResource.parse(text).info(paris)!!

    @Test
    fun `a new to-do has the fields of the draft and reads back the same`() {
        val draft =
            TodoDraft(
                summary = "Buy milk",
                done = false,
                due = LocalDateTime(2026, 11, 13, 18, 0),
                parentUid = "parent-1",
                sortOrder = 3,
            )
        val text = TodoResource.create("milk-1", draft, now).toIcs()
        assertTrue("DUE:20261113T180000\r\n" in text, text)
        assertTrue("TRIGGER;RELATED=END:PT0S" in text, text)
        assertTrue("RELATED-TO:parent-1" in text, text)
        assertTrue("X-APPLE-SORT-ORDER:3" in text, text)
        val info = info(text)
        assertEquals("milk-1", info.uid)
        assertEquals("Buy milk", info.summary)
        assertFalse(info.done)
        assertEquals(draft.due, info.due)
        assertEquals("parent-1", info.parentUid)
        assertEquals(3L, info.sortOrder)
        assertEquals(now, info.lastModified)
    }

    @Test
    fun `done comes from the status, the completed time or the percentage`() {
        val base = "UID:a\nDTSTAMP:20261101T000000Z\nSUMMARY:A\n"
        assertTrue(info(ics(base + "STATUS:COMPLETED")).done)
        assertTrue(info(ics(base + "STATUS:CANCELLED")).done)
        assertTrue(info(ics(base + "COMPLETED:20261102T100000Z")).done)
        assertTrue(info(ics(base + "PERCENT-COMPLETE:100")).done)
        assertFalse(info(ics(base + "STATUS:IN-PROCESS")).done)
        assertFalse(info(ics(base.trimEnd())).done)
    }

    @Test
    fun `due times in other forms become local times of the device`() {
        val base = "UID:a\nDTSTAMP:20261101T000000Z\n"
        assertEquals(
            LocalDateTime(2026, 11, 13, 9, 0),
            info(ics(base + "DUE;VALUE=DATE:20261113")).due,
        )
        assertEquals(
            LocalDateTime(2026, 11, 13, 18, 30),
            info(ics(base + "DUE:20261113T173000Z")).due,
        )
        assertEquals(
            LocalDateTime(2026, 11, 13, 18, 30),
            info(ics(base + "DUE;TZID=Europe/London:20261113T173000")).due,
        )
    }

    @Test
    fun `an edit changes only what differs and keeps unknown properties`() {
        val original =
            ics(
                """
                UID:a
                DTSTAMP:20261101T000000Z
                SUMMARY:Old title
                DUE;VALUE=DATE:20261113
                PRIORITY:1
                CATEGORIES:Home
                X-OTHER-APP:keep me
                SEQUENCE:2
                """
            )
        val resource = TodoResource.parse(original)
        val before = resource.info(paris)!!
        val same = TodoDraft("Old title", false, before.due, before.parentUid, sortOrder = 0)

        val edited = resource.edit(same.copy(summary = "New title"), paris, now).toIcs()
        // The due date without time stays as it was
        assertTrue("DUE;VALUE=DATE:20261113" in edited, edited)
        assertTrue("PRIORITY:1" in edited && "CATEGORIES:Home" in edited, edited)
        assertTrue("X-OTHER-APP:keep me" in edited, edited)
        assertTrue("SUMMARY:New title" in edited, edited)
        assertTrue("SEQUENCE:3" in edited, edited)
        assertEquals(now, info(edited).lastModified)
    }

    @Test
    fun `nothing to change gives the same resource`() {
        val resource =
            TodoResource.parse(
                ics("UID:a\nDTSTAMP:20261101T000000Z\nSUMMARY:A\nX-APPLE-SORT-ORDER:2")
            )
        assertSame(resource, resource.edit(TodoDraft("A", false, null, null, 2), paris, now))
    }

    @Test
    fun `done keeps the due date, and open again with a new time moves it`() {
        val resource =
            TodoResource.create(
                "a",
                TodoDraft("Rent", false, LocalDateTime(2026, 11, 13, 18, 0), null, 0),
                now,
            )
        // Grit clears the reminder of a done task: the server keeps the due date
        val done = resource.edit(TodoDraft("Rent", true, null, null, 0), paris, now)
        val doneInfo = done.info(paris)!!
        assertTrue(doneInfo.done)
        assertEquals(LocalDateTime(2026, 11, 13, 18, 0), doneInfo.due)
        assertTrue("STATUS:COMPLETED" in done.toIcs())
        assertTrue("PERCENT-COMPLETE:100" in done.toIcs())

        val reopened =
            done.edit(
                TodoDraft("Rent", false, LocalDateTime(2026, 12, 13, 18, 0), null, 0),
                paris,
                now,
            )
        val text = reopened.toIcs()
        assertFalse(reopened.info(paris)!!.done)
        assertTrue("STATUS:NEEDS-ACTION" in text, text)
        assertFalse("COMPLETED:" in text.replace("STATUS:COMPLETED", ""), text)
        assertEquals(1, Regex("BEGIN:VALARM").findAll(text).count(), text)
        assertEquals(LocalDateTime(2026, 12, 13, 18, 0), reopened.info(paris)!!.due)

        // No reminder: no due date and no alarm
        val cleared = reopened.edit(TodoDraft("Rent", false, null, null, 0), paris, now)
        assertNull(cleared.info(paris)!!.due)
        assertFalse("BEGIN:VALARM" in cleared.toIcs())
    }

    @Test
    fun `a parent link without type counts, a sibling link does not`() {
        val text =
            ics(
                "UID:child\nDTSTAMP:20261101T000000Z\n" +
                    "RELATED-TO;RELTYPE=SIBLING:other\nRELATED-TO:parent"
            )
        assertEquals("parent", info(text).parentUid)
        // Moving to the top level removes the parent link, not the sibling link
        val moved =
            TodoResource.parse(text).edit(TodoDraft("", false, null, null, 0), paris, now).toIcs()
        assertTrue("RELATED-TO;RELTYPE=SIBLING:other" in moved, moved)
        assertNull(info(moved).parentUid)
    }

    @Test
    fun `an event is not a to-do`() {
        val event =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\nUID:e\r\n" +
                "DTSTAMP:20261101T000000Z\r\nDTSTART:20261101T100000Z\r\nEND:VEVENT\r\n" +
                "END:VCALENDAR\r\n"
        assertFalse(TodoResource.isTodo(event))
        assertNull(TodoResource.parse(event).info(paris))
        assertNotNull(TodoResource.parse(ics("UID:t\nDTSTAMP:20261101T000000Z")).info(paris))
    }
}
