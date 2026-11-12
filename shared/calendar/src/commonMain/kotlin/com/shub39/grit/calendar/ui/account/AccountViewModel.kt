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
package com.shub39.grit.calendar.ui.account

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shub39.grit.caldav.AuthenticationException
import com.shub39.grit.caldav.ical.EventResource
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.CalendarSyncManager
import com.shub39.grit.calendar.data.PhoneCalendar
import com.shub39.grit.calendar.data.PhoneCalendars
import com.shub39.grit.calendar.data.db.AccountEntity
import com.shub39.grit.calendar.data.db.CategoryEntity
import com.shub39.grit.calendar.data.db.CategoryUse
import com.shub39.grit.calendar.data.db.CollectionEntity
import com.shub39.grit.calendar.tasks.TaskListSync
import com.shub39.grit.core.tasks.TaskRepo
import grit.shared.calendar.generated.resources.Res
import grit.shared.calendar.generated.resources.error_no_calendar_at_url
import grit.shared.calendar.generated.resources.error_wrong_login
import grit.shared.calendar.generated.resources.export_done
import grit.shared.calendar.generated.resources.export_failed
import grit.shared.calendar.generated.resources.import_no_events
import grit.shared.calendar.generated.resources.import_unreadable
import grit.shared.calendar.generated.resources.start_syncing_failed
import grit.shared.calendar.generated.resources.start_syncing_tasks_failed
import grit.shared.calendar.generated.resources.stop_syncing_deleted
import grit.shared.calendar.generated.resources.stop_syncing_kept_shared
import grit.shared.calendar.generated.resources.stop_syncing_kept_unknown
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@Immutable
data class AccountForm(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val connecting: Boolean = false,
    val error: String? = null,
)

@Immutable
data class AccountState(
    val account: AccountEntity? = null,
    val collections: List<CollectionEntity> = emptyList(),
    /** Calendars that exist only on this device. */
    val localCalendars: List<CollectionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    /** The calendars that hold events of each category, by category name. */
    val categoryUses: Map<String, List<CategoryUse>> = emptyMap(),
    /** False on desktop, which has no calendars of other apps. */
    val phoneSupported: Boolean = false,
    /** True if Grit may read the calendars of other apps, to import them. */
    val phonePermission: Boolean = false,
    val phoneCalendars: List<PhoneCalendar> = emptyList(),
    /** The view that the calendar opens with: 0 month, 1 week, 2 agenda. */
    val defaultView: Int = 0,
    val form: AccountForm = AccountForm(),
    val syncing: Boolean = false,
    val loaded: Boolean = false,
    /** Task lists of the server, synced or not. */
    val taskLists: List<TaskListUi> = emptyList(),
    /** Grit task categories that sync with no list. */
    val localTaskCategories: List<TaskCategoryUi> = emptyList(),
)

/** A task list of the server, and the Grit category it syncs with. */
@Immutable
data class TaskListUi(
    val id: Long,
    val name: String,
    /** Null if the list does not sync. */
    val categoryName: String?,
    val canWrite: Boolean,
    val error: String?,
)

/** A Grit task category. */
@Immutable data class TaskCategoryUi(val id: Long, val name: String)

/** A "Start syncing" that runs or failed. */
@Immutable
sealed interface SyncToggleUi {
    data object Running : SyncToggleUi

    /** What happened to the server copy after "Stop syncing". */
    data class Done(val message: String) : SyncToggleUi

    data class Failed(val message: String) : SyncToggleUi
}

/** The steps of an .ics import. */
@Immutable
sealed interface ImportUi {
    /** The file is read: the user chooses the calendar. */
    data class Choose(
        val fileName: String,
        val events: Int,
        /** If set, a new calendar on this device with this name is a target too. */
        val newCalendarName: String? = null,
    ) : ImportUi

    data object Importing : ImportUi

    data class Done(val added: Int, val updated: Int, val skipped: Int) : ImportUi

    data class Failed(val message: String) : ImportUi
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@KoinViewModel
class AccountViewModel(
    private val store: CalendarStore,
    private val syncManager: CalendarSyncManager,
    private val phone: PhoneCalendars,
    private val taskLists: TaskListSync,
    @Provided taskRepo: TaskRepo,
) : ViewModel() {
    private val form = MutableStateFlow(AccountForm())

    /** Null when no "Start syncing" runs. */
    val syncToggle = MutableStateFlow<SyncToggleUi?>(null)

    /** Null when no import is running. */
    val import = MutableStateFlow<ImportUi?>(null)

    /** The result of the last .ics export, or null. */
    val export = MutableStateFlow<String?>(null)
    private var importFile: List<EventResource> = emptyList()
    private var importName: String? = null

    /** Changes when the calendar permission might have changed, to read the calendars again. */
    private val phoneTick = MutableStateFlow(0)

    private val tasks: Flow<Pair<List<TaskListUi>, List<TaskCategoryUi>>> =
        combine(taskLists.lists, taskRepo.getTasksFlow().map { it.keys.toList() }) {
                lists,
                categories ->
                val names = categories.associate { it.id to it.name }
                // Lists of a removed account wait for their server: their categories are local
                val attached = lists.filter { it.accountId != null }
                val ui =
                    attached.map {
                        TaskListUi(
                            id = it.id,
                            name = it.displayName ?: it.url.trimEnd('/').substringAfterLast('/'),
                            categoryName = it.categoryId?.let { id -> names[id] ?: "" },
                            canWrite = it.canWrite,
                            error = it.lastError,
                        )
                    }
                val synced = attached.mapNotNull { it.categoryId }.toSet()
                ui to
                    categories
                        .filter { it.id !in synced }
                        .sortedBy { it.index }
                        .map { TaskCategoryUi(it.id, it.name) }
            }
            .distinctUntilChanged()

    val state: StateFlow<AccountState> =
        combine(
                store.accountsFlow(),
                store.collectionsFlow(),
                combine(store.categoriesFlow(), store.categoryUsesFlow(), ::Pair),
                combine(syncManager.running, form, store.defaultViewFlow(), ::Triple),
                combine(phoneTick.flatMapLatest { phone.calendars() }, tasks, ::Pair),
            ) {
                accounts,
                collections,
                (categories, categoryUses),
                (running, form, defaultView),
                (phoneCalendars, tasks) ->
                val account = accounts.firstOrNull { !it.local }
                val local = accounts.firstOrNull { it.local }
                AccountState(
                    account = account,
                    collections = collections.filter { it.accountId == account?.id },
                    localCalendars = collections.filter { it.accountId == local?.id },
                    categories = categories,
                    categoryUses = categoryUses,
                    phoneSupported = phone.supported,
                    phonePermission = phone.hasPermission(),
                    phoneCalendars = phoneCalendars,
                    defaultView = defaultView,
                    form = form,
                    syncing = running,
                    loaded = true,
                    taskLists = if (account == null) emptyList() else tasks.first,
                    localTaskCategories = tasks.second,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountState())

    fun updateForm(change: (AccountForm) -> AccountForm) =
        form.update { change(it).copy(error = null) }

    /** Logs in, saves the account with its calendars, and starts the first sync. */
    fun connect() {
        val current = form.value
        if (current.url.isBlank() || current.username.isBlank()) return
        form.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                val url = current.url.trim()
                val discovery = syncManager.discover(url, current.username.trim(), current.password)
                syncManager.addAccount(url, current.username.trim(), current.password, discovery)
                form.value = AccountForm()
                syncManager.syncAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationException) {
                val message = getString(Res.string.error_wrong_login)
                form.update { it.copy(connecting = false, error = message) }
            } catch (e: Exception) {
                form.update {
                    it.copy(connecting = false, error = e.message ?: e::class.simpleName)
                }
            }
        }
    }

    fun changePassword(password: String) {
        val account = state.value.account ?: return
        viewModelScope.launch {
            syncManager.updatePassword(account.id, password)
            syncManager.syncAll()
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncManager.syncAll() }
    }

    fun setVisible(collectionId: Long, visible: Boolean) {
        viewModelScope.launch { syncManager.setVisible(collectionId, visible) }
    }

    /** Creates the calendar on the server and uploads its events. */
    fun startSyncing(collectionId: Long) {
        syncToggle.value = SyncToggleUi.Running
        viewModelScope.launch {
            syncToggle.value =
                try {
                    syncManager.startSyncing(collectionId)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: AuthenticationException) {
                    SyncToggleUi.Failed(getString(Res.string.error_wrong_login))
                } catch (e: Exception) {
                    SyncToggleUi.Failed(
                        getString(
                            Res.string.start_syncing_failed,
                            e.message ?: e::class.simpleName.orEmpty(),
                        )
                    )
                }
        }
    }

    /** Keeps the calendar on this device only, and says what happened on the server. */
    fun stopSyncing(calendar: CollectionEntity) {
        syncToggle.value = SyncToggleUi.Running
        viewModelScope.launch {
            val name = calendar.displayName.orEmpty()
            val message =
                when (syncManager.stopSyncing(calendar.id)) {
                    CalendarSyncManager.StopResult.Deleted ->
                        getString(Res.string.stop_syncing_deleted, name)
                    CalendarSyncManager.StopResult.KeptShared ->
                        getString(Res.string.stop_syncing_kept_shared, name)
                    CalendarSyncManager.StopResult.KeptUnknown ->
                        getString(Res.string.stop_syncing_kept_unknown, name)
                }
            syncToggle.value = SyncToggleUi.Done(message)
        }
    }

    /** Syncs a server task list with a Grit category of the same name. */
    fun startSyncingTaskList(listId: Long) {
        viewModelScope.launch {
            taskLists.link(listId)
            syncManager.syncAll()
        }
    }

    /** Keeps the category and its tasks on this device, and the list on the server. */
    fun stopSyncingTaskList(listId: Long) {
        viewModelScope.launch { taskLists.unlink(listId) }
    }

    /** Creates a task list on the server for a Grit category and uploads its tasks. */
    fun startSyncingCategory(category: TaskCategoryUi) {
        syncToggle.value = SyncToggleUi.Running
        viewModelScope.launch {
            syncToggle.value =
                try {
                    syncManager.startSyncingTasks(category.id, category.name)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: AuthenticationException) {
                    SyncToggleUi.Failed(getString(Res.string.error_wrong_login))
                } catch (e: Exception) {
                    SyncToggleUi.Failed(
                        getString(
                            Res.string.start_syncing_tasks_failed,
                            e.message ?: e::class.simpleName.orEmpty(),
                        )
                    )
                }
        }
    }

    fun closeSyncToggle() {
        syncToggle.value = null
    }

    /** Adds a calendar by URL. Calls [onResult] with an error text, or null on success. */
    fun addByUrl(url: String, onResult: (String?) -> Unit) {
        val account = state.value.account ?: return
        viewModelScope.launch {
            val error =
                try {
                    if (syncManager.addCollectionByUrl(account.id, url.trim()) == null) {
                        getString(Res.string.error_no_calendar_at_url)
                    } else {
                        syncManager.syncAll()
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e::class.simpleName
                }
            onResult(error)
        }
    }

    /** Reads the calendars of other apps again, after the permission dialog. */
    fun onPhonePermission() {
        phoneTick.update { it + 1 }
    }

    /**
     * Reads a calendar of another app as iCalendar text, then offers the same choice as an .ics
     * file, with a new calendar on this device as an extra target.
     */
    fun importPhoneCalendar(calendar: PhoneCalendar) {
        import.value = ImportUi.Importing
        viewModelScope.launch {
            val resources =
                withContext(Dispatchers.Default) {
                    EventResource.splitFile(phone.exportIcs(calendar.id))
                }
            import.value =
                if (resources.isEmpty()) {
                    ImportUi.Failed(getString(Res.string.import_no_events))
                } else {
                    importFile = resources
                    importName = calendar.name
                    ImportUi.Choose(calendar.name, resources.size, newCalendarName = calendar.name)
                }
        }
    }

    fun setDefaultView(view: Int) {
        viewModelScope.launch { store.setDefaultView(view) }
    }

    /** Opens the file picker, then reads and splits the chosen .ics file. */
    fun pickIcsFile() {
        viewModelScope.launch {
            // No type filter: some apps save .ics files with a generic type
            val file = FileKit.openFilePicker(mode = FileKitMode.Single, type = FileKitType.File())
            if (file == null) return@launch
            import.value = ImportUi.Importing
            val resources =
                withContext(Dispatchers.Default) {
                    try {
                        EventResource.splitFile(file.readString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            import.value =
                when {
                    resources == null -> ImportUi.Failed(getString(Res.string.import_unreadable))
                    resources.isEmpty() -> ImportUi.Failed(getString(Res.string.import_no_events))
                    else -> {
                        importFile = resources
                        ImportUi.Choose(file.name, resources.size)
                    }
                }
        }
    }

    /** Imports into [collectionId], or into a new calendar on this device if it is null. */
    fun importInto(collectionId: Long?) {
        val resources = importFile
        val name = importName
        importFile = emptyList()
        importName = null
        import.value = ImportUi.Importing
        viewModelScope.launch {
            val target = collectionId ?: store.createLocalCalendar(name ?: "Imported")
            val result = withContext(Dispatchers.Default) { store.importEvents(target, resources) }
            import.value = ImportUi.Done(result.added, result.updated, result.skipped)
        }
    }

    fun closeImport() {
        importFile = emptyList()
        importName = null
        import.value = null
    }

    fun createLocalCalendar(name: String) {
        viewModelScope.launch { store.createLocalCalendar(name.trim()) }
    }

    fun createCategory(name: String, color: String) {
        if (name.isBlank()) return
        viewModelScope.launch { store.createCategory(name, color) }
    }

    fun setCategoryColor(name: String, color: String) {
        viewModelScope.launch { store.setCategoryColor(name, color) }
    }

    /** Renames a category (or only changes its color if the name stays). */
    fun updateCategory(oldName: String, newName: String, color: String) {
        viewModelScope.launch { store.renameCategory(oldName, newName, color) }
    }

    /** Saves the events of a calendar as an .ics file where the user chooses. */
    fun exportCalendar(calendar: CollectionEntity) {
        viewModelScope.launch {
            export.value =
                try {
                    val text = withContext(Dispatchers.Default) { store.exportIcs(calendar.id) }
                    val name =
                        (calendar.displayName ?: "calendar")
                            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                            .trim()
                            .ifEmpty { "calendar" }
                    val file =
                        FileKit.openFileSaver(suggestedName = name, defaultExtension = "ics")
                            ?: return@launch
                    file.writeString(text)
                    // One event per UID: a series and its changed occurrences count once
                    val events =
                        Regex("(?m)^UID:(.*)$")
                            .findAll(text)
                            .map { it.groupValues[1].trim() }
                            .toSet()
                            .size
                    getString(Res.string.export_done, events, file.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(Res.string.export_failed, e.message ?: e::class.simpleName.orEmpty())
                }
        }
    }

    fun closeExport() {
        export.value = null
    }

    fun deleteCategory(name: String) {
        viewModelScope.launch { store.deleteCategory(name) }
    }

    fun deleteLocalCalendar(collectionId: Long) {
        viewModelScope.launch { store.deleteLocalCalendar(collectionId) }
    }

    fun removeCollection(collectionId: Long) {
        viewModelScope.launch { syncManager.removeCollection(collectionId) }
    }

    fun removeAccount() {
        val account = state.value.account ?: return
        viewModelScope.launch { syncManager.removeAccount(account.id) }
    }
}
