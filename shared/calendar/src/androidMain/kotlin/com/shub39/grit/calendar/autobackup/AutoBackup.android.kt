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
package com.shub39.grit.calendar.autobackup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shub39.grit.core.settings.backup.ExportRepo
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

@Single
actual class AutoBackup(private val context: Context, @Provided private val export: ExportRepo) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("grit_auto_backup", Context.MODE_PRIVATE)
    private val resolver
        get() = context.contentResolver

    actual val supported: Boolean = true

    actual fun state(): Flow<AutoBackupState> = callbackFlow {
        trySend(read())
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun read() =
        AutoBackupState(
            frequency =
                BackupFrequency.entries.firstOrNull { it.name == prefs.getString(FREQUENCY, null) }
                    ?: BackupFrequency.Off,
            keep = prefs.getInt(KEEP, AutoBackupFiles.DEFAULT_KEEP),
            folderName = prefs.getString(FOLDER_NAME, null),
            lastBackup = prefs.getLong(LAST_BACKUP, 0L).takeIf { it > 0 },
            lastError = prefs.getString(LAST_ERROR, null),
        )

    actual fun setFrequency(frequency: BackupFrequency) {
        prefs.edit().putString(FREQUENCY, frequency.name).apply()
        schedule()
    }

    actual fun setKeep(keep: Int) {
        prefs.edit().putInt(KEEP, keep.coerceIn(1, AutoBackupFiles.MAX_KEEP)).apply()
    }

    actual fun setFolder(uri: String) {
        val tree = Uri.parse(uri)
        // Keep the access after a restart of the phone
        resolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        // Give up the access to the previous folder
        prefs
            .getString(FOLDER, null)
            ?.takeIf { it != uri }
            ?.let { old ->
                runCatching {
                    resolver.releasePersistableUriPermission(
                        Uri.parse(old),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        val name =
            runCatching {
                    displayName(DocumentsContract.buildDocumentUriUsingTree(tree, treeId(tree)))
                }
                .getOrNull() ?: tree.lastPathSegment
        prefs.edit().putString(FOLDER, uri).putString(FOLDER_NAME, name).remove(LAST_ERROR).apply()
        schedule()
    }

    actual suspend fun backUpNow(): Boolean =
        withContext(Dispatchers.IO) {
            val folder = prefs.getString(FOLDER, null)?.let(Uri::parse) ?: return@withContext false
            try {
                val text = export.exportJsonText()
                val parent = DocumentsContract.buildDocumentUriUsingTree(folder, treeId(folder))
                val name = AutoBackupFiles.fileName(LocalDateTime.now())
                val file =
                    DocumentsContract.createDocument(resolver, parent, "application/json", name)
                        ?: error("The folder refused a new file")
                resolver.openOutputStream(file, "wt")?.use { it.write(text.toByteArray()) }
                    ?: error("The new file cannot be written")
                removeOldBackups(folder)
                prefs
                    .edit()
                    .putLong(LAST_BACKUP, System.currentTimeMillis())
                    .remove(LAST_ERROR)
                    .apply()
                true
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.w(TAG, "Automatic backup failed", e)
                prefs.edit().putString(LAST_ERROR, e.message ?: e::class.simpleName).apply()
                false
            }
        }

    /** Deletes the automatic files beyond the number to keep. Other files stay. */
    private fun removeOldBackups(folder: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId(folder))
        val files = mutableMapOf<String, String>()
        resolver
            .query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) files[cursor.getString(1)] = cursor.getString(0)
            }
        val keep = prefs.getInt(KEEP, AutoBackupFiles.DEFAULT_KEEP)
        for (name in AutoBackupFiles.toDelete(files.keys.toList(), keep)) {
            val id = files.getValue(name)
            DocumentsContract.deleteDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(folder, id),
            )
        }
    }

    /** WorkManager runs [AutoBackupWorker] at the chosen interval. The first run is soon. */
    private fun schedule() {
        val work = WorkManager.getInstance(context)
        val frequency = read().frequency
        if (frequency == BackupFrequency.Off || prefs.getString(FOLDER, null) == null) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<AutoBackupWorker>(frequency.days, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun treeId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private fun displayName(document: Uri): String? =
        resolver
            .query(
                document,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private companion object {
        const val TAG = "AutoBackup"
        const val WORK_NAME = "grit_auto_backup"
        const val FREQUENCY = "frequency"
        const val KEEP = "keep"
        const val FOLDER = "folder"
        const val FOLDER_NAME = "folder_name"
        const val LAST_BACKUP = "last_backup"
        const val LAST_ERROR = "last_error"
    }
}

/** Writes one automatic backup. A failure waits for the next interval, it does not retry. */
class AutoBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        get<AutoBackup>().backUpNow()
        return Result.success()
    }
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { currentOnPicked(it.toString()) }
        }
    return remember(launcher) { { launcher.launch(null) } }
}
