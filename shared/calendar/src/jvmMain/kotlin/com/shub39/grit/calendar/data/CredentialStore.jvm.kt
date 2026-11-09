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

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import org.koin.core.annotation.Single

/**
 * Desktop (development target): passwords are stored in plain text in a file that only the user can
 * read.
 */
@Single
actual class CredentialStore {
    private val file = File(DesktopPaths.dataDir(), "calendar_credentials.properties")

    @Synchronized
    actual fun save(accountId: Long, password: String) {
        val properties = read()
        properties.setProperty(accountId.toString(), password)
        write(properties)
    }

    @Synchronized
    actual fun load(accountId: Long): String? = read().getProperty(accountId.toString())

    @Synchronized
    actual fun delete(accountId: Long) {
        val properties = read()
        properties.remove(accountId.toString())
        write(properties)
    }

    private fun read(): Properties =
        Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun write(properties: Properties) {
        if (!file.exists()) {
            file.createNewFile()
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    PosixFilePermissions.fromString("rw-------"),
                )
            }
        }
        file.outputStream().use { properties.store(it, null) }
    }
}
