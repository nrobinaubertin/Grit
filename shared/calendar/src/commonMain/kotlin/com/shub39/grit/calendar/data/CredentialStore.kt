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

import org.koin.core.annotation.Single

/** Keeps account passwords out of the database. */
@Single
expect class CredentialStore {
    fun save(accountId: Long, password: String)

    /**
     * The password, or null if it is missing or cannot be decrypted (for example after a restore).
     */
    fun load(accountId: Long): String?

    fun delete(accountId: Long)
}
