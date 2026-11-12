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
package com.shub39.grit.calendar

import com.shub39.grit.core.tasks.Category
import com.shub39.grit.core.tasks.SubTask
import com.shub39.grit.core.tasks.Task
import com.shub39.grit.core.tasks.TaskRepo
import com.shub39.grit.core.tasks.TaskWithSubTasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** An in-memory task list with one category, for tests of the calendar side of tasks. */
class FakeTaskRepo : TaskRepo {
    val category = Category(id = 1, name = "Inbox", color = "gray")
    val tasks = MutableStateFlow<List<Task>>(emptyList())
    private var nextId = 1L

    override fun getTasksFlow(): Flow<Map<Category, List<TaskWithSubTasks>>> =
        tasks.map { list -> mapOf(category to list.map { TaskWithSubTasks(it, emptyList()) }) }

    override fun getCompletedTasksFlow(): Flow<List<Task>> =
        tasks.map { list -> list.filter { it.status } }

    override suspend fun getTasks(): List<Task> = tasks.value

    override suspend fun getSubTasks(): List<SubTask> = emptyList()

    override suspend fun upsertSubTask(subTask: SubTask): Long = 0

    override suspend fun deleteSubTask(subTask: SubTask) = Unit

    override suspend fun getTaskById(id: Long): Task? = tasks.value.firstOrNull { it.id == id }

    override suspend fun getSubTaskById(id: Long): SubTask? = null

    override suspend fun getCategories(): List<Category> = listOf(category)

    override suspend fun updateTaskIndexById(id: Long, index: Int) = Unit

    override suspend fun upsertTask(task: Task): Long {
        val id = if (task.id == 0L) nextId++ else task.id
        tasks.update { list -> list.filter { it.id != id } + task.copy(id = id) }
        return id
    }

    override suspend fun deleteTask(task: Task) {
        tasks.update { list -> list.filter { it.id != task.id } }
    }

    override suspend fun deleteAllTasks() {
        tasks.value = emptyList()
    }

    override suspend fun upsertCategory(category: Category) = Unit

    override suspend fun deleteCategory(category: Category) = Unit

    override suspend fun deleteAllCategories() = Unit
}
