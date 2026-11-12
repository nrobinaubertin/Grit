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
package com.shub39.grit.calendar.tasks

import com.shub39.grit.caldav.AuthenticationException
import com.shub39.grit.caldav.CollectionInfo
import com.shub39.grit.caldav.DavClient
import com.shub39.grit.caldav.ical.TodoResource
import com.shub39.grit.caldav.sync.CollectionSyncer
import com.shub39.grit.calendar.data.CalendarStore
import com.shub39.grit.calendar.data.db.CalendarDatabase
import com.shub39.grit.calendar.data.db.ConflictEntity
import com.shub39.grit.calendar.data.db.TaskItemEntity
import com.shub39.grit.calendar.data.db.TaskListEntity
import com.shub39.grit.core.interfaces.AlarmScheduler
import com.shub39.grit.core.tasks.Category
import com.shub39.grit.core.tasks.SubTask
import com.shub39.grit.core.tasks.Task
import com.shub39.grit.core.tasks.TaskRepo
import com.shub39.grit.shared.ui.navigation.TaskSync
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Two-way sync between Grit task categories and task lists (VTODO collections) on the CalDAV
 * server. One category syncs with one list. Grit stores tasks in its own database, which knows
 * nothing of the server, so this class finds Grit changes by comparison: each synced task has a row
 * with the Grit fields as last seen. A change made anywhere in Grit (task list, widget,
 * notification) shows as a difference.
 */
@OptIn(FlowPreview::class)
@Single(binds = [TaskSync::class])
class TaskListSync(
    db: CalendarDatabase,
    private val store: CalendarStore,
    @Provided private val taskRepo: TaskRepo,
    @Provided private val alarms: AlarmScheduler,
) : TaskSync {
    private val dao = db.taskListDao()
    private val calendarDao = db.calendarDao()
    private val clock: Clock = Clock.System
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val zone: ZoneId
        get() = ZoneId.systemDefault()

    /** Every task list of the server, synced or not. */
    val lists: Flow<List<TaskListEntity>> = dao.listsFlow()

    init {
        // After a change in Grit, find it and ask for a sync
        scope.launch {
            taskRepo.getTasksFlow().debounce(1_000).collect {
                try {
                    if (detectAll()) store.requestSync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next sync finds the change too
                }
            }
        }
    }

    /** Syncs soon if a task list syncs, for example when the task list opens. */
    override fun requestSync() {
        scope.launch { if (dao.linkedLists().isNotEmpty()) store.requestSync() }
    }

    /** Stops the background work. The app never needs it, tests close the database after it. */
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    /** Finds Grit changes in every synced list. Returns true if a list has something to upload. */
    suspend fun detectAll(): Boolean =
        mutex.withLock {
            var pending = false
            // A list that waits for its first sync uploads nothing yet
            for (list in dao.linkedLists()) if (list.paired && detect(list)) pending = true
            pending
        }

    /**
     * Adds the task lists that discovery found and updates the known ones. A list that the server
     * no longer has stops syncing; its Grit category and tasks stay.
     */
    suspend fun merge(accountId: Long, found: List<CollectionInfo>) =
        mutex.withLock {
            val tasks = found.filter { it.supportsTasks }
            val existing = dao.lists(accountId)
            for (info in tasks) {
                val match = existing.firstOrNull { sameUrl(it.url, info.url) }
                if (match == null) {
                    dao.insertList(
                        TaskListEntity(
                            accountId = accountId,
                            url = info.url,
                            displayName = info.displayName,
                            canWrite = info.canWrite,
                        )
                    )
                } else {
                    dao.updateList(
                        match.copy(displayName = info.displayName, canWrite = info.canWrite)
                    )
                }
            }
            for (list in existing) {
                if (tasks.none { sameUrl(it.url, list.url) }) dao.deleteList(list.id)
            }
        }

    /** Adds a list that Grit just created on the server for [categoryId]. */
    suspend fun addCreated(accountId: Long, url: String, name: String, categoryId: Long): Long =
        mutex.withLock {
            dao.insertList(
                TaskListEntity(
                    accountId = accountId,
                    url = url,
                    displayName = name,
                    categoryId = categoryId,
                    // A new list is empty: nothing to pair
                    paired = true,
                )
            )
        }

    /**
     * Syncs the server list [listId] with a Grit category: the category [categoryId], or a category
     * with the name of the list (a new one if none is free). The first sync pairs tasks that have
     * the same title on both sides, so that linking the same list again makes no copies.
     */
    suspend fun link(listId: Long, categoryId: Long? = null) =
        mutex.withLock {
            val list = dao.list(listId) ?: return@withLock
            val linked = dao.linkedLists().mapNotNull { it.categoryId }.toSet()
            val target =
                categoryId
                    ?: findOrCreateCategory(
                        list.displayName ?: list.url.trimEnd('/').substringAfterLast('/'),
                        linked,
                    )
            dao.deleteItems(list.id)
            dao.updateList(
                list.copy(categoryId = target, paired = false, syncToken = null, lastError = null)
            )
            store.requestSync()
        }

    /**
     * Stops syncing [listId]. The Grit category and its tasks stay, and so does the server list.
     */
    suspend fun unlink(listId: Long) = mutex.withLock { dao.list(listId)?.let { unlinkLocked(it) } }

    private suspend fun unlinkLocked(list: TaskListEntity) {
        dao.deleteItems(list.id)
        dao.updateList(
            list.copy(categoryId = null, paired = false, syncToken = null, lastError = null)
        )
    }

    private suspend fun findOrCreateCategory(name: String, linked: Set<Long>): Long {
        val categories = taskRepo.getCategories()
        categories
            .firstOrNull { it.name == name && it.id !in linked }
            ?.let {
                return it.id
            }
        val index = (categories.maxOfOrNull { it.index } ?: -1) + 1
        taskRepo.upsertCategory(Category(name = name, index = index, color = ""))
        // The repository does not return the new id: the newest category with this name
        return taskRepo.getCategories().filter { it.name == name }.maxOf { it.id }
    }

    /** Syncs every linked list of the account, and stores the result on each list. */
    suspend fun syncAccount(dav: DavClient, accountId: Long) {
        for (list in dao.linkedLists().filter { it.accountId == accountId }) {
            val error =
                try {
                    sync(dav, list.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthenticationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e::class.simpleName
                }
            dao.setSyncResult(list.id, clock.now().toEpochMilliseconds(), error)
        }
    }

    /**
     * Syncs one list that is linked to a category. Returns the first error, or null. The first sync
     * of a link downloads and pairs tasks of the same title before anything uploads, so that
     * linking a list again makes no copies.
     */
    suspend fun sync(dav: DavClient, listId: Long): String? =
        mutex.withLock {
            var list = active(listId) ?: return@withLock null
            val syncer = CollectionSyncer(dav)
            if (!list.paired) {
                val errors = syncer.sync(TaskCollection(this, dao, list, zone)).errors
                materialize(list, pair = true)
                if (errors.isNotEmpty()) return@withLock errors.first()
                dao.setPaired(list.id)
            }
            // Changes made in Grit since the last check. It can also stop the sync
            detect(list)
            list = active(listId) ?: return@withLock null
            val errors = syncer.sync(TaskCollection(this, dao, list, zone)).errors
            materialize(list, pair = false)
            errors.firstOrNull()
        }

    private suspend fun active(listId: Long): TaskListEntity? =
        dao.list(listId)?.takeIf { it.categoryId != null }

    // --- Grit to rows --------------------------------------------------------------------------

    /** Compares the Grit tasks of the list's category with the rows. Returns true on a change. */
    private suspend fun detect(list: TaskListEntity): Boolean {
        val categoryId = list.categoryId ?: return false
        if (taskRepo.getCategories().none { it.id == categoryId }) {
            // The category was deleted in Grit: stop syncing, and never delete the server tasks
            unlinkLocked(list)
            return false
        }
        val tasks = taskRepo.getTasks().filter { it.categoryId == categoryId }.associateBy { it.id }
        val subTasks = taskRepo.getSubTasks().filter { it.taskId in tasks }.associateBy { it.id }
        val items = dao.items(list.id)
        val now = clock.now().toEpochMilliseconds()
        val seenTasks = HashSet<Long>()
        val seenSubTasks = HashSet<Long>()
        var changed = false
        for (item in items) {
            item.taskId?.let { seenTasks += it }
            item.subTaskId?.let { seenSubTasks += it }
            // A server change waits to be applied; that sets the snapshot
            if (item.deleted || item.remoteChanged) continue
            val current =
                when {
                    item.taskId != null -> tasks[item.taskId]?.let { snapshot(it) }
                    item.subTaskId != null -> subTasks[item.subTaskId]?.let { snapshot(it) }
                    else -> continue
                }
            if (current == null) {
                // Deleted in Grit, or moved to another category. The id is free again: SQLite can
                // give it to a new task
                if (item.existsOnServer) {
                    dao.updateItem(
                        item.copy(
                            deleted = true,
                            taskId = null,
                            subTaskId = null,
                            version = item.version + 1,
                            modifiedAt = now,
                        )
                    )
                } else {
                    dao.deleteItem(item.id)
                }
                changed = true
            } else if (current != item.snapshot) {
                dao.updateItem(
                    item.copy(
                        snapshot = current,
                        dirty = true,
                        version = item.version + 1,
                        modifiedAt = now,
                    )
                )
                changed = true
            }
        }
        // New in Grit. Tasks first, so that subtasks find the UID of their parent
        for (task in tasks.values.sortedBy { it.id }) {
            if (task.id !in seenTasks) {
                dao.insertItem(newItem(list, now).copy(taskId = task.id, snapshot = snapshot(task)))
                changed = true
            }
        }
        for (subTask in subTasks.values.sortedBy { it.id }) {
            if (subTask.id !in seenSubTasks) {
                dao.insertItem(
                    newItem(list, now).copy(subTaskId = subTask.id, snapshot = snapshot(subTask))
                )
                changed = true
            }
        }
        return changed
    }

    private fun newItem(list: TaskListEntity, now: Long): TaskItemEntity {
        val uid = UUID.randomUUID().toString()
        return TaskItemEntity(
            listId = list.id,
            href = list.url.trimEnd('/') + "/" + uid + ".ics",
            uid = uid,
            dirty = true,
            version = 1,
            existsOnServer = false,
            modifiedAt = now,
        )
    }

    /** What to upload for [item], from the Grit task or subtask. Null if it no longer exists. */
    internal suspend fun draftOf(item: TaskItemEntity, items: List<TaskItemEntity>): GritDraft? {
        item.taskId?.let { id ->
            val task = taskRepo.getTaskById(id) ?: return null
            return GritDraft(task.title, task.status, task.reminder, null, task.index)
        }
        item.subTaskId?.let { id ->
            val subTask = taskRepo.getSubTaskById(id) ?: return null
            val parentUid = items.firstOrNull { it.taskId == subTask.taskId }?.uid
            return GritDraft(
                subTask.title,
                subTask.status,
                subTask.reminder,
                parentUid,
                subTask.index,
            )
        }
        return null
    }

    // --- rows to Grit --------------------------------------------------------------------------

    /**
     * Applies the server versions that the last sync downloaded to the Grit tasks. Top-level to-dos
     * first, then the to-dos with a parent. Grit has one level of subtasks: a to-do under a subtask
     * becomes a subtask of the top task. If [pair] is true, a new to-do takes a Grit task of the
     * same title that no row uses yet, instead of making a new one.
     */
    private suspend fun materialize(list: TaskListEntity, pair: Boolean) {
        val categoryId = list.categoryId ?: return
        val changedRows = dao.remoteChangedItems(list.id)
        if (changedRows.isEmpty()) return
        val zone = zone
        val parsed =
            changedRows.mapNotNull { row ->
                val info = runCatching { TodoResource.parse(row.ics).info(zone) }.getOrNull()
                if (info == null) {
                    // Not a to-do Grit can read: keep the row so that the server copy is known
                    dao.updateItem(row.copy(remoteChanged = false))
                    null
                } else row to info
            }
        val byUid = dao.items(list.id).associateBy { it.uid }
        // To-dos without an order value take the next places in the order of their creation, so
        // that every phone puts them in the same order, whatever order they arrive in
        val (children, topLevel) =
            parsed
                .sortedWith(
                    compareBy({ it.second.created ?: Instant.DISTANT_FUTURE }, { it.second.uid })
                )
                .partition { (_, info) -> info.parentUid != null && info.parentUid in byUid }

        for ((row, info) in topLevel) applyTask(list, categoryId, row, info, pair)
        for ((row, info) in children) {
            val parentTask = rootTaskId(list, info.parentUid!!, depth = 0)
            if (parentTask == null) applyTask(list, categoryId, row, info, pair)
            else applySubTask(row, info, parentTask, pair)
        }
    }

    /** The Grit task at the top of the parent chain of [uid], or null. */
    private suspend fun rootTaskId(list: TaskListEntity, uid: String, depth: Int): Long? {
        if (depth > 8) return null
        val row = dao.items(list.id).firstOrNull { it.uid == uid } ?: return null
        row.taskId?.let {
            return it
        }
        row.subTaskId?.let {
            return taskRepo.getSubTaskById(it)?.taskId
        }
        val parent =
            runCatching { TodoResource.parse(row.ics).info(zone)?.parentUid }.getOrNull()
                ?: return null
        return rootTaskId(list, parent, depth + 1)
    }

    private suspend fun applyTask(
        list: TaskListEntity,
        categoryId: Long,
        row: TaskItemEntity,
        info: com.shub39.grit.caldav.ical.TodoInfo,
        pair: Boolean,
    ) {
        // A former subtask: it becomes a task
        row.subTaskId?.let { id -> taskRepo.getSubTaskById(id)?.let { removeSubTask(it) } }
        val title = info.summary.orEmpty()
        val existing =
            row.taskId?.let { taskRepo.getTaskById(it) }
                ?: if (pair) unpairedTask(list, categoryId, title) else null
        val index =
            info.sortOrder?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                ?: existing?.index
                ?: nextIndex(categoryId)
        val task =
            Task(
                id = existing?.id ?: 0,
                categoryId = existing?.categoryId ?: categoryId,
                title = title,
                index = index,
                status = info.done,
                reminder = if (info.done) null else info.due,
            )
        val id = taskRepo.upsertTask(task)
        val saved = taskRepo.getTaskById(id) ?: task.copy(id = id)
        if (saved.status) alarms.cancel(saved) else alarms.schedule(saved)
        dao.updateItem(
            row.copy(
                taskId = id,
                subTaskId = null,
                snapshot = snapshot(saved),
                remoteChanged = false,
                dirty = false,
            )
        )
    }

    private suspend fun applySubTask(
        row: TaskItemEntity,
        info: com.shub39.grit.caldav.ical.TodoInfo,
        parentTaskId: Long,
        pair: Boolean,
    ) {
        // A former task: it becomes a subtask
        row.taskId?.let { id -> taskRepo.getTaskById(id)?.let { removeTask(it) } }
        val title = info.summary.orEmpty()
        val existing =
            row.subTaskId?.let { taskRepo.getSubTaskById(it) }?.takeIf { it.taskId == parentTaskId }
                ?: if (pair) unpairedSubTask(row.listId, parentTaskId, title) else null
        // Moved to another parent: Grit cannot move a subtask, so it makes a new one
        row.subTaskId
            ?.let { taskRepo.getSubTaskById(it) }
            ?.takeIf { it.taskId != parentTaskId }
            ?.let { removeSubTask(it) }
        val index =
            info.sortOrder?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                ?: existing?.index
                ?: ((taskRepo
                    .getSubTasks()
                    .filter { it.taskId == parentTaskId }
                    .maxOfOrNull { it.index } ?: -1) + 1)
        val subTask =
            SubTask(
                id = existing?.id ?: 0,
                taskId = parentTaskId,
                title = title,
                status = info.done,
                index = index,
                reminder = if (info.done) null else info.due,
            )
        val id = taskRepo.upsertSubTask(subTask)
        val saved = taskRepo.getSubTaskById(id) ?: subTask.copy(id = id)
        if (saved.status) alarms.cancel(saved) else alarms.schedule(saved)
        dao.updateItem(
            row.copy(
                taskId = null,
                subTaskId = id,
                snapshot = snapshot(saved),
                remoteChanged = false,
                dirty = false,
            )
        )
    }

    private suspend fun unpairedTask(list: TaskListEntity, categoryId: Long, title: String): Task? {
        val used = dao.items(list.id).mapNotNullTo(HashSet()) { it.taskId }
        return taskRepo.getTasks().firstOrNull {
            it.categoryId == categoryId && it.title == title && it.id !in used
        }
    }

    private suspend fun unpairedSubTask(listId: Long, parentTaskId: Long, title: String): SubTask? {
        val used = dao.items(listId).mapNotNullTo(HashSet()) { it.subTaskId }
        return taskRepo.getSubTasks().firstOrNull {
            it.taskId == parentTaskId && it.title == title && it.id !in used
        }
    }

    private suspend fun nextIndex(categoryId: Long): Int =
        (taskRepo.getTasks().filter { it.categoryId == categoryId }.maxOfOrNull { it.index }
            ?: -1) + 1

    /** Deletes a Grit task that the server deleted, with its alarm. */
    internal suspend fun removeTask(task: Task) {
        alarms.cancel(task)
        taskRepo.getSubTasks().filter { it.taskId == task.id }.forEach { alarms.cancel(it) }
        taskRepo.deleteTask(task)
    }

    internal suspend fun removeSubTask(subTask: SubTask) {
        alarms.cancel(subTask)
        taskRepo.deleteSubTask(subTask)
    }

    /** Deletes the Grit task or subtask of [item], for a deletion on the server. */
    internal suspend fun removeLocal(item: TaskItemEntity) {
        item.taskId?.let { id -> taskRepo.getTaskById(id)?.let { removeTask(it) } }
        item.subTaskId?.let { id -> taskRepo.getSubTaskById(id)?.let { removeSubTask(it) } }
    }

    /** Keeps the losing side of a conflict, like the calendar does for events. */
    internal suspend fun recordConflict(href: String, localIcs: String, remoteIcs: String?) {
        calendarDao.insertConflict(
            ConflictEntity(
                collectionId = 0,
                href = href,
                localIcs = localIcs,
                remoteIcs = remoteIcs,
                createdAt = clock.now().toEpochMilliseconds(),
            )
        )
    }

    private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    companion object {
        private const val SEPARATOR = "\u001F"

        /** The Grit fields of a task, as one comparable text. */
        fun snapshot(task: Task): String =
            listOf("T", task.title, task.status, task.reminder, task.index).joinToString(SEPARATOR)

        fun snapshot(subTask: SubTask): String =
            listOf(
                    "S",
                    subTask.taskId,
                    subTask.title,
                    subTask.status,
                    subTask.reminder,
                    subTask.index,
                )
                .joinToString(SEPARATOR)
    }
}

/** The Grit fields that go into a to-do. */
internal data class GritDraft(
    val title: String,
    val done: Boolean,
    val reminder: kotlinx.datetime.LocalDateTime?,
    val parentUid: String?,
    val index: Int,
)
