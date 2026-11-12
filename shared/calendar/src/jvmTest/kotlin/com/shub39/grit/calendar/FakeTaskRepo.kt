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

import com.shub39.grit.core.habits.Habit
import com.shub39.grit.core.interfaces.AlarmScheduler
import com.shub39.grit.core.tasks.Category
import com.shub39.grit.core.tasks.SubTask
import com.shub39.grit.core.tasks.Task
import com.shub39.grit.core.tasks.TaskRepo
import com.shub39.grit.core.tasks.TaskWithSubTasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory task repository: categories (one "Inbox" to start with), tasks and subtasks.
 * Deleting a category deletes its tasks, and deleting a task deletes its subtasks, as in Grit.
 */
class FakeTaskRepo : TaskRepo {
    val category = Category(id = 1, name = "Inbox", color = "gray")
    val categories = MutableStateFlow(listOf(category))
    val tasks = MutableStateFlow<List<Task>>(emptyList())
    val subTasks = MutableStateFlow<List<SubTask>>(emptyList())
    private var nextId = 1L
    private var nextCategoryId = 2L

    override fun getTasksFlow(): Flow<Map<Category, List<TaskWithSubTasks>>> =
        combine(categories, tasks, subTasks) { categories, tasks, subTasks ->
            categories.associateWith { category ->
                tasks
                    .filter { it.categoryId == category.id }
                    .sortedBy { it.index }
                    .map { task ->
                        TaskWithSubTasks(task, subTasks.filter { it.taskId == task.id })
                    }
            }
        }

    override fun getCompletedTasksFlow(): Flow<List<Task>> =
        tasks.map { list -> list.filter { it.status } }

    override suspend fun getTasks(): List<Task> = tasks.value

    override suspend fun getSubTasks(): List<SubTask> = subTasks.value

    override suspend fun upsertSubTask(subTask: SubTask): Long {
        val id = if (subTask.id == 0L) nextId++ else subTask.id
        subTasks.update { list -> list.filter { it.id != id } + subTask.copy(id = id) }
        return id
    }

    override suspend fun deleteSubTask(subTask: SubTask) {
        subTasks.update { list -> list.filter { it.id != subTask.id } }
    }

    override suspend fun getTaskById(id: Long): Task? = tasks.value.firstOrNull { it.id == id }

    override suspend fun getSubTaskById(id: Long): SubTask? =
        subTasks.value.firstOrNull { it.id == id }

    override suspend fun getCategories(): List<Category> = categories.value

    override suspend fun updateTaskIndexById(id: Long, index: Int) {
        tasks.update { list -> list.map { if (it.id == id) it.copy(index = index) else it } }
    }

    override suspend fun upsertTask(task: Task): Long {
        val id = if (task.id == 0L) nextId++ else task.id
        tasks.update { list -> list.filter { it.id != id } + task.copy(id = id) }
        return id
    }

    override suspend fun deleteTask(task: Task) {
        tasks.update { list -> list.filter { it.id != task.id } }
        subTasks.update { list -> list.filter { it.taskId != task.id } }
    }

    override suspend fun deleteAllTasks() {
        tasks.value = emptyList()
        subTasks.value = emptyList()
    }

    override suspend fun upsertCategory(category: Category) {
        val id = if (category.id == 0L) nextCategoryId++ else category.id
        categories.update { list -> list.filter { it.id != id } + category.copy(id = id) }
    }

    override suspend fun deleteCategory(category: Category) {
        val gone = tasks.value.filter { it.categoryId == category.id }.map { it.id }.toSet()
        categories.update { list -> list.filter { it.id != category.id } }
        tasks.update { list -> list.filter { it.id !in gone } }
        subTasks.update { list -> list.filter { it.taskId !in gone } }
    }

    override suspend fun deleteAllCategories() {
        categories.value = emptyList()
        deleteAllTasks()
    }
}

/** Alarms that never ring: the JVM tests have no notifications. */
object NoAlarms : AlarmScheduler {
    override fun schedule(habit: Habit) = Unit

    override fun schedule(task: Task) = Unit

    override fun schedule(subTask: SubTask) = Unit

    override fun cancel(habit: Habit) = Unit

    override fun cancel(task: Task) = Unit

    override fun cancel(subTask: SubTask) = Unit

    override fun cancelAll() = Unit
}
