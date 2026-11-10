/*
 * Copyright (C) 2026  Shubham Gorai
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
package com.shub39.grit.core.settings.backup

import kotlinx.serialization.json.JsonElement

/**
 * Adds the data of a feature to the backup file, next to tasks and habits. Each contributor writes
 * under its own [key] in `extensions`. Older Grit versions ignore unknown keys when they restore.
 */
interface BackupContributor {
    /** Key of the data in the backup file. It never changes. */
    val key: String

    suspend fun export(): JsonElement

    /** Merges [data] into the existing data. It never deletes anything. */
    suspend fun restore(data: JsonElement)
}
